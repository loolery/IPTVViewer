package com.example.iptvviewer

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// Datenklasse für eine Playlist
data class Playlist(val name: String, val url: String)

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: Toolbar

    private lateinit var channelAdapter: ChannelAdapter
    private val fullChannelList = mutableListOf<Channel>()
    private var downloadID: Long = -1L

    private lateinit var progressBar: ProgressBar
    private val PREFS_NAME = "IPTVViewerPrefs"
    private val PLAYLISTS_KEY = "playlists"
    private val LAST_OPENED_PLAYLIST_URL_KEY = "last_opened_playlist_url"


    private val requestInstallPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (packageManager.canRequestPackageInstalls()) {
                installUpdate()
            } else {
                Toast.makeText(this, "Berechtigung zur Installation nicht erteilt.", Toast.LENGTH_LONG).show()
            }
        }

    private val onDownloadComplete: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadID) {
                Toast.makeText(this@MainActivity, "Download abgeschlossen", Toast.LENGTH_SHORT).show()
                installUpdate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        progressBar = findViewById(R.id.progressBar)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        navigationView.setNavigationItemSelectedListener(this)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        setupRecyclerView()
        updateNavMenu()
        setupVersionTextView() // Versionsanzeige initialisieren

        // Verbesserte Startlogik
        val lastUrl = getLastOpenedPlaylistUrl()
        val playlists = getPlaylists()
        if (lastUrl != null && playlists.any { it.url == lastUrl }) {
            loadChannelsFromUrl(lastUrl)
            toolbar.title = playlists.find { it.url == lastUrl }?.name
        } else if (playlists.isNotEmpty()) {
            val firstPlaylist = playlists.first()
            loadChannelsFromUrl(firstPlaylist.url)
            saveLastOpenedPlaylistUrl(firstPlaylist.url)
            toolbar.title = firstPlaylist.name
        } else {
            showAddPlaylistDialog()
        }

        checkForUpdates()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    // ÜBERARBEITET: Verwendet den globalen findViewById der Aktivität
    private fun setupVersionTextView() {
        try {
            // Sicherer, aktivitätsweiter Zugriff auf das TextView
            val versionTextView = findViewById<TextView>(R.id.version_text_view)
            if (versionTextView != null) {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                versionTextView.text = getString(R.string.version_placeholder, packageInfo.versionName)
            } else {
                Log.e("MainActivity", "version_text_view nicht gefunden.")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Fehler beim Setzen des Versionstextes", e)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.nav_add_playlist) {
            showAddPlaylistDialog()
        } else {
            val playlists = getPlaylists()
            if (item.order < playlists.size) {
                val selectedPlaylist = playlists[item.order]
                loadChannelsFromUrl(selectedPlaylist.url)
                saveLastOpenedPlaylistUrl(selectedPlaylist.url)
                toolbar.title = selectedPlaylist.name
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun updateNavMenu() {
        val menu = navigationView.menu
        menu.removeGroup(R.id.playlist_group)

        val playlists = getPlaylists()
        playlists.forEachIndexed { index, playlist ->
            val menuItem = menu.add(R.id.playlist_group, Menu.NONE, index, playlist.name)
            menuItem.setIcon(R.drawable.ic_playlist)
            menuItem.setActionView(R.layout.playlist_menu_item)

            val deleteButton = menuItem.actionView?.findViewById<View>(R.id.delete_button)
            deleteButton?.setOnClickListener {
                showDeleteConfirmationDialog(playlist)
            }
        }
        menu.setGroupCheckable(R.id.playlist_group, true, true)
    }

    private fun showDeleteConfirmationDialog(playlist: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("Playlist löschen")
            .setMessage("Möchtest du die Playlist \"${playlist.name}\" wirklich löschen?")
            .setPositiveButton("Löschen") { _, _ ->
                deletePlaylist(playlist)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun deletePlaylist(playlistToDelete: Playlist) {
        val playlists = getPlaylists().toMutableList()
        playlists.removeAll { it.url == playlistToDelete.url }
        savePlaylists(playlists)
        updateNavMenu()

        if (getLastOpenedPlaylistUrl() == playlistToDelete.url) {
            val nextPlaylist = getPlaylists().firstOrNull()
            if (nextPlaylist != null) {
                loadChannelsFromUrl(nextPlaylist.url)
                saveLastOpenedPlaylistUrl(nextPlaylist.url)
                toolbar.title = nextPlaylist.name
            } else {
                fullChannelList.clear()
                channelAdapter.updateList(emptyList())
                toolbar.title = getString(R.string.app_name)
                saveLastOpenedPlaylistUrl("")
                showAddPlaylistDialog()
            }
        }
    }

    private fun getTextFromUrl(urlString: String): String {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line).append('\n')
            }
            reader.close()
            return response.toString()
        } finally {
            connection?.disconnect()
        }
    }

    private fun loadChannelsFromUrl(url: String) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val m3uString = getTextFromUrl(url)
                val channels = M3uParser.parse(m3uString)
                withContext(Dispatchers.Main) {
                    fullChannelList.clear()
                    fullChannelList.addAll(channels)
                    channelAdapter.updateList(fullChannelList)
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Fehler beim Laden der Kanalliste von $url", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Fehler beim Laden der Liste: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAddPlaylistDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Neue Playlist hinzufügen")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 20)

        val nameInput = EditText(this)
        nameInput.hint = "Name der Playlist"
        layout.addView(nameInput)

        val urlInput = EditText(this)
        urlInput.hint = "M3U URL"
        layout.addView(urlInput)

        builder.setView(layout)

        builder.setPositiveButton("Speichern") { dialog, _ ->
            val name = nameInput.text.toString()
            val url = urlInput.text.toString()
            if (name.isNotBlank() && url.isNotBlank()) {
                addPlaylist(Playlist(name, url))
                updateNavMenu()
                loadChannelsFromUrl(url)
                saveLastOpenedPlaylistUrl(url)
                toolbar.title = name
            } else {
                Toast.makeText(this, "Name und URL dürfen nicht leer sein.", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        builder.setNegativeButton("Abbrechen") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }


    private fun savePlaylists(playlists: List<Playlist>) {
        val jsonArray = JSONArray()
        playlists.forEach {
            val jsonObject = JSONObject()
            jsonObject.put("name", it.name)
            jsonObject.put("url", it.url)
            jsonArray.put(jsonObject)
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PLAYLISTS_KEY, jsonArray.toString()).apply()
    }

    private fun getPlaylists(): List<Playlist> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(PLAYLISTS_KEY, null) ?: return emptyList()

        return try {
            val playlists = mutableListOf<Playlist>()
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                playlists.add(Playlist(jsonObject.getString("name"), jsonObject.getString("url")))
            }
            playlists
        } catch (e: JSONException) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun addPlaylist(playlist: Playlist) {
        val playlists = getPlaylists().toMutableList()
        playlists.add(playlist)
        savePlaylists(playlists)
    }

    private fun saveLastOpenedPlaylistUrl(url: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(LAST_OPENED_PLAYLIST_URL_KEY, url).apply()
    }

    private fun getLastOpenedPlaylistUrl(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(LAST_OPENED_PLAYLIST_URL_KEY, null)
    }


    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(onDownloadComplete)
    }

    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonString = getTextFromUrl("https://benevolent-stardust-255e9c.netlify.app/update.json")
                val json = JSONObject(jsonString)
                val latestVersionCode = json.getInt("versionCode")
                val apkUrl = json.optString("apkUrl", "")

                if (apkUrl.isBlank()) {
                    return@launch
                }

                val currentVersionCode = getCurrentVersionCode()

                if (latestVersionCode > currentVersionCode) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(apkUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Fehler bei der Update-Prüfung", e)
            }
        }
    }

    private fun showUpdateDialog(apkUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Neues Update verfügbar")
            .setMessage("Eine neue Version der App ist verfügbar. Möchten Sie sie jetzt herunterladen?")
            .setPositiveButton("Jetzt aktualisieren") { dialog, _ ->
                downloadAndInstallUpdate(apkUrl)
                dialog.dismiss()
            }
            .setNegativeButton("Später") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstallUpdate(apkUrl: String) {
        val fileName = "update.apk"
        val downloadUri = Uri.parse(apkUrl)
        val request = DownloadManager.Request(downloadUri)
            .setMimeType("application/vnd.android.package-archive")
            .setTitle("IPTV Viewer Update")
            .setDescription("Lade die neueste Version herunter...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadID = downloadManager.enqueue(request)
        Toast.makeText(this, "Download gestartet...", Toast.LENGTH_SHORT).show()
    }

    private fun installUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this)
                    .setTitle("Berechtigung erforderlich")
                    .setMessage("Um die App zu aktualisieren, müssen Sie die Berechtigung zur Installation aus unbekannten Quellen erteilen.")
                    .setPositiveButton("Zu den Einstellungen") { dialog, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:$packageName"))
                        requestInstallPermissionLauncher.launch(intent)
                        dialog.dismiss()
                    }
                    .setNegativeButton("Abbrechen") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
                return
            }
        }

        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val resInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Fehler bei der Installation: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
             Toast.makeText(this, "Fehler: Update-Datei nicht gefunden.", Toast.LENGTH_LONG).show()
        }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionCode
        } catch (e: Exception) {
            -1
        }
    }

    private fun setupRecyclerView() {
        val recyclerView: RecyclerView = findViewById(R.id.recyclerViewChannels)
        recyclerView.layoutManager = LinearLayoutManager(this)
        channelAdapter = ChannelAdapter(mutableListOf()) { clickedChannel ->
            playStreamInExternalPlayer(clickedChannel.streamUrl)
        }
        recyclerView.adapter = channelAdapter
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterChannels(newText)
                return true
            }
        })
        return super.onCreateOptionsMenu(menu)
    }

    private fun filterChannels(query: String?) {
        val filteredList = if (query.isNullOrBlank()) {
            fullChannelList
        } else {
            fullChannelList.filter {
                it.name.contains(query, ignoreCase = true)
            }
        }
        channelAdapter.updateList(filteredList)
    }

    private fun playStreamInExternalPlayer(streamUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.parse(streamUrl), "video/*")
        startActivity(intent)
    }
}