package com.example.smarttrackapp.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.smarttrackapp.databinding.ItemVehicleBinding
import com.example.smarttrackapp.models.Vehicle

class VehicleAdapter(
    private val listener: OnVehicleClickListener
) : ListAdapter<Vehicle, VehicleAdapter.VehicleViewHolder>(VehicleDiffCallback()) {

    interface OnVehicleClickListener {
        fun onVehicleClick(vehicle: Vehicle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VehicleViewHolder {
        val binding = ItemVehicleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VehicleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VehicleViewHolder, position: Int) {
        val vehicle = getItem(position)
        holder.bind(vehicle, listener)
    }

    class VehicleViewHolder(private val binding: ItemVehicleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(vehicle: Vehicle, listener: OnVehicleClickListener) {
            binding.apply {
                vehicleName.text = vehicle.nickname
                vehicleNumber.text = vehicle.phoneNumber
                vehicleStatus.text = if (vehicle.isImmobilized) "Immobilized" else "Active"

                root.setOnClickListener {
                    listener.onVehicleClick(vehicle)
                }
            }
        }
    }
}

class VehicleDiffCallback : DiffUtil.ItemCallback<Vehicle>() {
    override fun areItemsTheSame(oldItem: Vehicle, newItem: Vehicle): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Vehicle, newItem: Vehicle): Boolean {
        return oldItem == newItem
    }
}