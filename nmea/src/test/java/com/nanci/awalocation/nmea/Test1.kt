package com.nanci.awalocation.nmea

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class Test1 {
    private val data = setOf(
        "\$GPGSA,A,1,,,,,,,,,,,,,,,,*32",
        "\$GPVTG,,T,,M,,N,,K,N*2C",
        "\$GPDTM,,,,,,,,*4A",
        "\$GPRMC,,V,,,,,,,,,,N,V*29",
        "\$GPGNS,,,,,,N,,,,,,,V*79",
        "\$GPGGA,,,,,,0,,,,,,,,*66",
        "\$GPGSV,3,1,09,03,29,281,,04,18,319,,16,60,239,,26,73,355,,1*60",
        "\$GPGSV,3,2,09,27,18,180,,28,39,060,,29,15,049,,31,55,010,,1*6E",
        "\$GPGSV,3,3,09,32,29,137,,1*52",
        "\$GLGSV,2,1,08,14,43,011,,16,07,248,,15,46,288,,05,38,326,,1*72",
        "\$GPGGA,,,,,,0,,,,,,,,*66",
        "\$GPGNS,,,,,,N,,,,,,,V*79",
        "\$GQGSV,1,1,03,02,64,078,,03,12,142,,04,44,162,,1*5F",
        "\$GPGSV,1,1,02,03,29,281,42,04,18,319,35*7E",
    )

    @Test
    fun roundTripNmeaSentences() {
        data.forEach {
            val nmea = NMEA.valueOf(it)
            assertEquals("NMEA mismatch for $it", it, nmea.toNmeaString())
        }
    }

    @Test
    fun ddmmConversionProducesExpectedRange() {
        val lat = 28.139908
        val lon = 113.8939
        val degree = lat.toInt()
        val minute = (lat - degree) * 60
        val latitude = degree * 100 + minute

        val degree2 = lon.toInt()
        val minute2 = (lon - degree2) * 60
        val longitude = degree2 * 100 + minute2

        assertEquals(2808.39448, latitude, 1e-4)
        assertEquals(11353.634, longitude, 1e-4)
    }

    companion object {
        val HUNDRED = BigDecimal(100)
        val SIXTY = BigDecimal(60)
    }
}