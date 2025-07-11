package com.example.smarttrackapp.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.smarttrackapp.databinding.ItemTripHistoryBinding

import com.example.smarttrackapp.models.TripHistory
import java.text.SimpleDateFormat
import java.util.*

class TripHistoryAdapter(
    private val onItemClick: (TripHistory) -> Unit = {}
) : ListAdapter<TripHistory, TripHistoryAdapter.TripHistoryViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripHistoryViewHolder {
        val binding = ItemTripHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TripHistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TripHistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TripHistoryViewHolder(
        private val binding: ItemTripHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TripHistory) {
            binding.apply {
                latitudeText.text = "Lat: %.6f".format(item.latitude)
                longitudeText.text = "Lng: %.6f".format(item.longitude)
                timestampText.text = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
                    .format(Date(item.timestamp))
                eventTypeText.text = item.eventType.replace("_", " ")
                root.setOnClickListener { onItemClick(item) }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TripHistory>() {
        override fun areItemsTheSame(oldItem: TripHistory, newItem: TripHistory): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TripHistory, newItem: TripHistory): Boolean {
            return oldItem == newItem
        }
    }
}