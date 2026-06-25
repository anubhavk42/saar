package com.example.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormatter {
    fun formatIsoDateToHumanReadable(isoDateStr: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = parser.parse(isoDateStr)
            if (date != null) {
                val formatter = SimpleDateFormat("EEEE, MMMM d", Locale.US)
                formatter.format(date)
            } else {
                isoDateStr
            }
        } catch (e: Exception) {
            isoDateStr
        }
    }
}
