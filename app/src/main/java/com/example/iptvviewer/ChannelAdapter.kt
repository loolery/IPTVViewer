package com.example.iptvviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private var channels: MutableList<Channel>,
    private val onChannelClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val channelName: TextView = itemView.findViewById(R.id.channelName)

        private val indentSpace: View = itemView.findViewById(R.id.indent_space)

        fun bind(channel: Channel, onChannelClick: (Channel) -> Unit) {
            channelName.text = channel.name

            // Prüfen, ob der Kanal ein Gruppentitel ist
            val isSpecial = channel.name.isNotEmpty() && !channel.name.first().isLetterOrDigit()

            if (isSpecial) {
                // GRUPPENTITEL Einrücken
                indentSpace.visibility = View.VISIBLE
                // Hintergrundfarbe setzen
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.special_channel_background))
                // unklickbar
                itemView.setOnClickListener(null)

            } else {
                // STIL FÜR normale Kanäle, Abstandhalter unsichtbar
                indentSpace.visibility = View.GONE
                // Hintergrund zurücksetzen
                itemView.setBackgroundResource(android.R.drawable.list_selector_background)
                // Klickbar machen
                itemView.setOnClickListener { onChannelClick(channel) }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(channels[position], onChannelClick)
    }

    override fun getItemCount(): Int {
        return channels.size
    }



    fun updateList(newList: List<Channel>) {
        channels.clear()
        channels.addAll(newList)
        notifyDataSetChanged()
    }
}