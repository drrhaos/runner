package com.drrhaos.runner.util

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.drrhaos.runner.R
import com.drrhaos.runner.ui.tracking.GpsStatus

/**
 * Утилитный класс для обработки ошибок и fallback механизмов
 */
object ErrorHandler {
    
    private const val TAG = "ErrorHandler"
    
    /**
     * Проверяет доступность GPS
     */
    fun isGpsAvailable(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
    
    /**
     * Проверяет доступность сети
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            networkInfo?.isConnected == true
        }
    }
    
    /**
     * Определяет GPS статус на основе доступности и точности
     */
    fun determineGpsStatus(
        context: Context,
        accuracy: Float,
        lastUpdateTime: Long
    ): GpsStatus {
        val currentTime = System.currentTimeMillis()
        
        return when {
            !isGpsAvailable(context) -> {
                Log.w(TAG, "GPS provider not available")
                GpsStatus.DENIED
            }
            currentTime - lastUpdateTime > 30000 -> { // 30 секунд без обновления
                Log.w(TAG, "GPS signal lost - no updates for ${currentTime - lastUpdateTime}ms")
                GpsStatus.LOST
            }
            accuracy > 100 -> {
                Log.w(TAG, "GPS accuracy too low: ${accuracy}m")
                GpsStatus.SEARCHING
            }
            else -> {
                Log.d(TAG, "GPS signal found with accuracy: ${accuracy}m")
                GpsStatus.FOUND
            }
        }
    }
    
    /**
     * Обрабатывает ошибки GPS с пользовательскими уведомлениями
     */
    fun handleGpsError(context: Context, gpsStatus: GpsStatus, showToast: Boolean = true) {
        val message = when (gpsStatus) {
            GpsStatus.DENIED -> context.getString(R.string.gps_denied_message)
            GpsStatus.LOST -> context.getString(R.string.gps_lost_message)
            GpsStatus.SEARCHING -> context.getString(R.string.gps_searching_message)
            GpsStatus.WEAK -> context.getString(R.string.gps_weak_message)
            GpsStatus.MEDIUM -> context.getString(R.string.gps_medium_message)
            GpsStatus.STRONG -> context.getString(R.string.gps_strong_message)
            GpsStatus.FOUND -> return
        }
        
        Log.w(TAG, "GPS Error: $message")
        
        if (showToast) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Обрабатывает ошибки сохранения данных
     */
    fun handleSaveError(context: Context, error: Throwable, showToast: Boolean = true) {
        val message = when (error) {
            is java.sql.SQLException -> context.getString(R.string.error_database_save)
            is java.io.IOException -> context.getString(R.string.error_io_save)
            else -> context.getString(R.string.error_save_format, error.message)
        }
        
        Log.e(TAG, "Save Error: $message", error)
        
        if (showToast) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Обрабатывает ошибки загрузки данных
     */
    fun handleLoadError(context: Context, error: Throwable, showToast: Boolean = true) {
        val message = when (error) {
            is java.sql.SQLException -> context.getString(R.string.error_database_load)
            is java.io.IOException -> context.getString(R.string.error_io_load)
            else -> context.getString(R.string.error_load_format, error.message)
        }
        
        Log.e(TAG, "Load Error: $message", error)
        
        if (showToast) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Retry механизм с экспоненциальной задержкой
     */
    suspend fun <T> retryWithBackoff(
        maxRetries: Int = 3,
        initialDelay: Long = 1000L,
        maxDelay: Long = 10000L,
        operation: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay = (currentDelay * 2).coerceAtMost(maxDelay)
                }
            }
        }
        
        throw lastException ?: Exception("All retry attempts failed")
    }
}
