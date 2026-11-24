package com.nanci.awalocation.ui.mock

import java.math.BigDecimal

data class HistoricalLocation(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double
) {
    companion object {
        // Format: "name","address","lat","lon"
        fun fromString(str: String): HistoricalLocation {
            // CSV parser supporting commas inside quoted fields
            val fields = mutableListOf<String>()
            var currentField = StringBuilder()
            var inQuotes = false
            
            var i = 0
            while (i < str.length) {
                val char = str[i]
                when {
                    char == '"' && (i + 1 >= str.length || str[i + 1] != '"') -> {
                        // Toggle quote state
                        inQuotes = !inQuotes
                    }
                    char == '"' && i + 1 < str.length && str[i + 1] == '"' -> {
                        // Handle escaped quotes ("") 
                        currentField.append('"')
                        // Skip next quote
                        i++
                    }
                    char == ',' && !inQuotes -> {
                        // Comma as separator
                        fields.add(currentField.toString().trim())
                        currentField = StringBuilder()
                    }
                    else -> {
                        // Regular character
                        currentField.append(char)
                    }
                }
                i++
            }
            
            // Add the last field
            fields.add(currentField.toString().trim())
            
            if (fields.size != 4) {
                throw IllegalArgumentException("Invalid format. Expected 4 fields but got ${fields.size}: $str")
            }
            
            return HistoricalLocation(
                name = fields[0].trim('"'),
                address = fields[1].trim('"'),
                lat = fields[2].trim('"').toDouble(),
                lon = fields[3].trim('"').toDouble()
            )
        }
    }

    override fun toString(): String {
        val plainLat = BigDecimal.valueOf(lat).toPlainString()
        val plainLon = BigDecimal.valueOf(lon).toPlainString()

        fun escapeCsv(field: String): String {
            val escaped = field.replace("\"", "\"\"")
            return if (field.contains(",") || field.contains("\"")) "\"$escaped\"" else field
        }

        return "${escapeCsv(name)},${escapeCsv(address)},$plainLat,$plainLon"
    }
}
