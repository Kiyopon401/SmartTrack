// Add this to your Application class or in onCreate() of VehicleDetailActivity:

private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Geofence alerts channel
        val geofenceChannel = NotificationChannel(
            "geofence_channel",
            "Geofence Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for geofence entry/exit events"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000)
            enableLights(true)
            lightColor = Color.RED
        }
        
        // Service channel
        val serviceChannel = NotificationChannel(
            "service_channel",
            "Location Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background location tracking service"
        }
        
        notificationManager.createNotificationChannel(geofenceChannel)
        notificationManager.createNotificationChannel(serviceChannel)
    }
}

// Call this in onCreate() after setContentView():
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityVehicleDetailBinding.inflate(layoutInflater)
    setContentView(binding.root)
    
    // Create notification channels
    createNotificationChannels()
    
    // ... rest of your onCreate code
}