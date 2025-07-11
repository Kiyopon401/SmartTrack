package com.example.smarttrackapp.activities

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smarttrackapp.R
import com.example.smarttrackapp.databinding.ActivityTripHistoryBinding
import com.example.smarttrackapp.ViewModels.TripHistoryViewModel
import com.example.smarttrackapp.adapters.TripHistoryAdapter
import com.example.smarttrackapp.data.AppDatabase
import com.example.smarttrackapp.di.TripHistoryViewModelFactory
import com.example.smarttrackapp.models.TripHistory
import com.example.smarttrackapp.utils.MapUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import com.google.firebase.database.*
import android.util.Log

class TripHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripHistoryBinding
    private lateinit var viewModel: TripHistoryViewModel
    private lateinit var adapter: TripHistoryAdapter
    private var refreshJob: Job? = null
    private var vehicleId: Long = -1L
    private var allHistoryPoints = emptyList<TripHistory>()
    private var playbackJob: Job? = null
    private val MAX_TRAIL_POINTS = 100 // Limit trail to last 100 points
    private var playbackPaused = false
    private var playbackSpeedMs = 1200 // Default 1200ms per step
    private var refreshIntervalMs = 5000 // Default 5 seconds
    private var mapLoading = false
    private var mapLoadError = false
    private lateinit var resetZoomBtn: Button
    private lateinit var pauseResumeBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var speedSlider: SeekBar
    private lateinit var refreshSlider: SeekBar
    private var tripPointsListener: ChildEventListener? = null
    private lateinit var firebaseDb: FirebaseDatabase
    private var lastTripHistoryUpdateTime = 0L // Add this for UI throttling
    private val TRIP_HISTORY_UPDATE_THROTTLE_MS = 3000L // 3 seconds throttle for UI updates
    private val MIN_DISTANCE_METERS = 5.0 // Only add if moved at least 5 meters
    private var lastMapUpdateTime = 0L
    private val MAP_UPDATE_THROTTLE_MS = 1000L // 1 second
    private var isLoading = true
    private val MAX_DISPLAY_POINTS = 500

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTripHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Trip History"

        vehicleId = intent.getLongExtra("vehicle_id", -1L)
        if (vehicleId == -1L) {
            finish()
            return
        }

        val dao = AppDatabase.getDatabase(applicationContext).tripHistoryDao()
        viewModel = ViewModelProvider(
            this,
            TripHistoryViewModelFactory(dao)
        )[TripHistoryViewModel::class.java]

        // In onCreate, after initializing viewModel:
        isLoading = true
        binding.mapLoadingSpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getTripHistory(vehicleId).collectLatest { history ->
                    allHistoryPoints = history.sortedBy { it.timestamp }
                    isLoading = false
                    updateTripHistoryUI()
                }
            }
        }
        // Remove attachTripPointsListener() and any direct Firebase trip points logic.

        // Add Reset Zoom button above the map
        resetZoomBtn = Button(this).apply {
            text = "Reset Zoom"
            visibility = View.GONE
            setOnClickListener {
                binding.tripMapWebView.evaluateJavascript("resetMapZoom()", null)
            }
        }
        // Insert above the map
        val mapParent = binding.tripMapWebView.parent as? LinearLayout
        mapParent?.addView(resetZoomBtn, mapParent.indexOfChild(binding.tripMapWebView))

        MapUtils.initializeMap(
            binding.tripMapWebView,
            null,
            0.0,
            0.0,
            htmlFile = "map_trip_history.html",
            fromAssets = true,
            onMapReadyCallback = {
                MapUtils.drawTripPath(binding.tripMapWebView, emptyList())
            },
            onMapErrorCallback = {
                mapLoadError = true
                runOnUiThread {
                    binding.mapLoadingSpinner.visibility = View.GONE
                    binding.mapErrorText.visibility = View.VISIBLE
                }
            },
            onMapLoadingCallback = { isLoading ->
                mapLoading = isLoading
                runOnUiThread {
                    binding.mapLoadingSpinner.visibility = if (isLoading) View.VISIBLE else View.GONE
                    if (!isLoading && !mapLoadError) {
                        binding.mapErrorText.visibility = View.GONE
                    }
                }
            }
        )
    }

    // Remove setupClickListeners()
    // Remove refreshTripHistory()
    // Remove clearTripHistory()

    override fun onResume() {
        super.onResume()
        // startPeriodicRefresh() // Removed periodic refresh
    }

    override fun onPause() {
        super.onPause()
        // stopPeriodicRefresh() // Removed periodic refresh
        playbackJob?.cancel()
    }

    // Remove periodic refresh logic (startPeriodicRefresh, stopPeriodicRefresh, refreshJob)

    private fun throttledMapUpdate(updateBlock: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastMapUpdateTime >= MAP_UPDATE_THROTTLE_MS) {
            lastMapUpdateTime = now
            updateBlock()
        }
    }

    private fun updateTripHistoryUI() {
        if (isLoading) {
            binding.mapLoadingSpinner.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
            return
        }
        val displayPoints = if (allHistoryPoints.size > MAX_DISPLAY_POINTS) allHistoryPoints.takeLast(MAX_DISPLAY_POINTS) else allHistoryPoints
        if (displayPoints.isNotEmpty()) {
            binding.mapLoadingSpinner.visibility = View.GONE
            throttledMapUpdate {
                try {
                    Log.d("TripHistory", "Updating map with ${displayPoints.size} points")
                    updateMapWithHistory(displayPoints)
                } catch (e: Exception) {
                    Log.e("TripHistory", "Error updating map", e)
                    Toast.makeText(this, "Error drawing map: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
            binding.statsLayout.visibility = View.VISIBLE
            binding.totalPointsText.text = "Points: ${allHistoryPoints.size} (showing last ${displayPoints.size})"
            binding.timeRangeText.text = "From ${formatDate(displayPoints.last().timestamp)} to ${formatDate(displayPoints.first().timestamp)}"
            binding.emptyState.visibility = View.GONE
        } else {
            binding.mapLoadingSpinner.visibility = View.GONE
            binding.statsLayout.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        }
        // Remove updatePlaybackControls()
    }

    private fun showMapLoadingSpinner(show: Boolean) {
        runOnUiThread {
            binding.mapLoadingSpinner.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun updateMapWithHistory(history: List<TripHistory>) {
        try {
            if (mapLoadError || mapLoading) return // Defensive: skip if map not ready
            if (history.isNotEmpty()) {
                val path = history.map { Pair(it.latitude, it.longitude) }
                MapUtils.drawTripPath(binding.tripMapWebView, path)
                resetZoomBtn.visibility = View.VISIBLE
                // Add start and end markers
                MapUtils.updateMapLocation(
                    binding.tripMapWebView,
                    history.first().latitude,
                    history.first().longitude,
                    "Start: ${formatDate(history.first().timestamp)}",
                    true
                )
                MapUtils.updateMapLocation(
                    binding.tripMapWebView,
                    history.last().latitude,
                    history.last().longitude,
                    "End: ${formatDate(history.last().timestamp)}",
                    true
                )
            } else {
                resetZoomBtn.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e("TripHistory", "Error updating map", e)
            runOnUiThread {
                Toast.makeText(this, "Error drawing map: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Remove setupPlaybackControls()
    // Remove updatePlaybackControls()
    // Remove stopPlayback()
    // Remove playTripHistoryAnimated()

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    override fun onDestroy() {
        try {
            playbackJob?.cancel()
            // Release map resources
            MapUtils.clearInitialized(binding.tripMapWebView)
        } catch (e: Exception) {
            Log.e("TripHistory", "Error in onDestroy", e)
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        try {
            onBackPressed()
        } catch (e: Exception) {
            Log.e("TripHistory", "Error on back press", e)
            finish()
        }
        return true
    }
}