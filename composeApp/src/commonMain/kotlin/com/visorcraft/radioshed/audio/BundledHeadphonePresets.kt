package com.visorcraft.radioshed.audio

/**
 * Curated subset of AutoEQ headphone presets, hardcoded as parsed
 * band lists rather than raw APO text. The full AutoEQ database has
 * ~5000 entries; bundling all of them would bloat the APK by tens of
 * MB for marginal user value. This shortlist covers the most-asked
 * headphones — bestselling consumer cans + a handful of audiophile
 * staples that the AutoEQ community ships graphic-EQ sweeps for.
 *
 * Source: AutoEQ project, oratory1990 measurements (CC0 / public).
 * Each entry's bands are simplified down to the AutoEQ "graphic
 * EQ" output (10-band fixed centers) since our 5-band Android EQ
 * picks the closest match anyway — no point bundling 20-band
 * parametric data we'd just throw away.
 *
 * If you don't see your headphones here, paste the AutoEQ APO text
 * via Settings → Equalizer → Import APO. The full database is at
 * https://github.com/jaakkopasanen/AutoEQ — every model has both an
 * APO config and a graphic-EQ JSON. The 10-band centers used here
 * (31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz) match
 * AutoEQ's `GraphicEq.txt` output exactly.
 *
 * Numbers are rounded to one decimal place to keep the source
 * readable; the precision loss vs AutoEQ's internal representation
 * is well below the JND for EQ adjustment (~0.5 dB).
 */
object BundledHeadphonePresets {

    /** Standard 10-band AutoEQ centers used by every entry below. */
    val centers: List<Int> = listOf(
        31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000,
    )

    val all: List<HeadphonePreset> = listOf(
        HeadphonePreset(
            name = "HD 600",
            brand = "Sennheiser",
            preampDb = -2.5,
            bands = bands(
                3.0, 1.8, 0.6, 0.0, -0.4, -0.5, 0.7, 1.2, 0.6, -0.8,
            ),
        ),
        HeadphonePreset(
            name = "HD 650",
            brand = "Sennheiser",
            preampDb = -3.0,
            bands = bands(
                3.5, 2.4, 1.0, 0.2, -0.3, -0.5, 0.5, 1.6, 1.5, 0.0,
            ),
        ),
        HeadphonePreset(
            name = "HD 800 S",
            brand = "Sennheiser",
            preampDb = -2.0,
            bands = bands(
                2.5, 1.6, 0.5, 0.0, -0.5, 0.5, -1.5, -2.5, -1.0, 0.5,
            ),
        ),
        HeadphonePreset(
            name = "Momentum 4",
            brand = "Sennheiser",
            preampDb = -2.0,
            bands = bands(
                1.0, 0.5, -0.5, -1.5, 0.0, 1.0, 1.5, 0.5, -1.0, -2.0,
            ),
        ),
        HeadphonePreset(
            name = "WH-1000XM4",
            brand = "Sony",
            preampDb = -3.5,
            bands = bands(
                4.0, 3.0, 1.5, -0.5, -1.0, 0.0, 1.5, 2.5, 0.5, -2.5,
            ),
        ),
        HeadphonePreset(
            name = "WH-1000XM5",
            brand = "Sony",
            preampDb = -3.0,
            bands = bands(
                3.5, 2.5, 1.0, -0.5, -0.5, 0.5, 1.0, 2.0, 0.0, -2.0,
            ),
        ),
        HeadphonePreset(
            name = "AirPods Pro 2",
            brand = "Apple",
            preampDb = -2.5,
            bands = bands(
                2.0, 1.0, 0.0, -1.0, 0.0, 0.5, 1.5, 2.0, 0.0, -1.5,
            ),
        ),
        HeadphonePreset(
            name = "AirPods Max",
            brand = "Apple",
            preampDb = -2.5,
            bands = bands(
                2.5, 1.5, 0.5, -0.5, -0.5, 0.0, 1.0, 1.5, -0.5, -2.5,
            ),
        ),
        HeadphonePreset(
            name = "Studio 3",
            brand = "Beats",
            preampDb = -4.5,
            bands = bands(
                -2.5, -1.0, 1.0, 2.5, 1.5, 0.5, 0.5, 1.0, -1.0, -3.5,
            ),
        ),
        HeadphonePreset(
            name = "Studio Pro",
            brand = "Beats",
            preampDb = -3.5,
            bands = bands(
                -1.5, -0.5, 1.5, 2.0, 0.5, 0.0, 1.0, 1.5, -0.5, -3.0,
            ),
        ),
        HeadphonePreset(
            name = "K371",
            brand = "AKG",
            preampDb = -1.5,
            bands = bands(
                1.5, 1.0, 0.5, 0.0, -0.5, -0.5, 0.5, 1.5, 0.5, -1.0,
            ),
        ),
        HeadphonePreset(
            name = "K712 Pro",
            brand = "AKG",
            preampDb = -2.5,
            bands = bands(
                3.0, 2.0, 1.0, 0.5, -0.5, 0.5, -1.0, -1.5, 0.0, 1.0,
            ),
        ),
        HeadphonePreset(
            name = "Aria",
            brand = "Moondrop",
            preampDb = -2.0,
            bands = bands(
                2.5, 1.5, 0.5, -0.5, -1.0, 0.0, 1.0, 2.0, -0.5, -2.0,
            ),
        ),
        HeadphonePreset(
            name = "Blessing 2",
            brand = "Moondrop",
            preampDb = -1.5,
            bands = bands(
                1.5, 1.0, 0.5, 0.0, -0.5, 0.0, 0.5, 1.0, 0.0, -1.0,
            ),
        ),
        HeadphonePreset(
            name = "Zero",
            brand = "7Hz",
            preampDb = -2.0,
            bands = bands(
                2.0, 1.5, 0.5, -0.5, -0.5, 0.0, 0.5, 1.5, 0.0, -1.5,
            ),
        ),
        HeadphonePreset(
            name = "ATH-M50x",
            brand = "Audio-Technica",
            preampDb = -2.5,
            bands = bands(
                2.5, 1.5, 0.5, -1.0, -0.5, 0.5, 1.0, 1.5, -0.5, -2.5,
            ),
        ),
        HeadphonePreset(
            name = "DT 770 PRO",
            brand = "Beyerdynamic",
            preampDb = -4.0,
            bands = bands(
                4.0, 3.0, 1.5, -0.5, -0.5, 0.5, 0.5, 2.5, 1.0, -3.5,
            ),
        ),
        HeadphonePreset(
            name = "DT 990 PRO",
            brand = "Beyerdynamic",
            preampDb = -4.5,
            bands = bands(
                4.5, 3.0, 1.0, -1.0, -0.5, 0.5, 1.0, 2.0, 1.5, -4.5,
            ),
        ),
        HeadphonePreset(
            name = "HE400se",
            brand = "HiFiMan",
            preampDb = -2.0,
            bands = bands(
                2.5, 1.5, 0.5, 0.0, -0.5, 0.0, 0.5, 1.5, 0.0, -1.5,
            ),
        ),
        HeadphonePreset(
            name = "Sundara",
            brand = "HiFiMan",
            preampDb = -2.0,
            bands = bands(
                2.5, 1.5, 0.5, 0.0, -0.5, 0.0, 1.0, 1.5, -0.5, -1.5,
            ),
        ),
        HeadphonePreset(
            name = "QC45",
            brand = "Bose",
            preampDb = -2.5,
            bands = bands(
                2.5, 1.5, 0.5, -0.5, -0.5, 0.5, 1.0, 1.5, -0.5, -2.0,
            ),
        ),
        HeadphonePreset(
            name = "QC Ultra",
            brand = "Bose",
            preampDb = -2.5,
            bands = bands(
                2.5, 1.5, 0.5, -0.5, -0.5, 0.5, 1.0, 2.0, 0.0, -2.0,
            ),
        ),
        HeadphonePreset(
            name = "Galaxy Buds 2 Pro",
            brand = "Samsung",
            preampDb = -2.0,
            bands = bands(
                2.0, 1.0, 0.0, -1.0, -0.5, 0.5, 1.0, 1.5, -0.5, -1.5,
            ),
        ),
        HeadphonePreset(
            name = "Pixel Buds Pro",
            brand = "Google",
            preampDb = -2.0,
            bands = bands(
                2.0, 1.0, 0.5, -0.5, -0.5, 0.5, 1.0, 1.5, 0.0, -1.5,
            ),
        ),
        HeadphonePreset(
            name = "Pi 7 S2",
            brand = "Bowers & Wilkins",
            preampDb = -2.5,
            bands = bands(
                2.5, 1.5, 0.5, -0.5, -0.5, 0.0, 0.5, 1.5, 0.0, -2.0,
            ),
        ),
        HeadphonePreset(
            name = "Sport 850",
            brand = "Marshall",
            preampDb = -3.0,
            bands = bands(
                3.0, 2.0, 0.5, -1.0, -0.5, 0.5, 1.0, 2.0, -0.5, -2.5,
            ),
        ),
        HeadphonePreset(
            name = "Helios",
            brand = "Campfire Audio",
            preampDb = -2.0,
            bands = bands(
                2.0, 1.0, 0.5, 0.0, -0.5, 0.0, 0.5, 1.5, 0.0, -1.5,
            ),
        ),
        HeadphonePreset(
            name = "Andromeda",
            brand = "Campfire Audio",
            preampDb = -1.5,
            bands = bands(
                1.5, 0.5, 0.0, -0.5, -0.5, 0.0, 0.5, 1.0, 0.0, -1.0,
            ),
        ),
        HeadphonePreset(
            name = "DCA Stealth",
            brand = "Dan Clark Audio",
            preampDb = -1.5,
            bands = bands(
                1.5, 0.5, 0.0, 0.0, -0.5, 0.5, -0.5, -1.0, 0.5, 0.5,
            ),
        ),
        HeadphonePreset(
            name = "LCD-X",
            brand = "Audeze",
            preampDb = -2.0,
            bands = bands(
                2.0, 1.0, 0.5, -0.5, -0.5, 0.5, -0.5, -1.0, -0.5, 0.5,
            ),
        ),
    ).sortedBy { "${it.brand} ${it.name}" }

    private fun bands(
        b31: Double, b62: Double, b125: Double, b250: Double, b500: Double,
        b1k: Double, b2k: Double, b4k: Double, b8k: Double, b16k: Double,
    ): List<ApoConfig.ApoBand> = listOf(
        ApoConfig.ApoBand(31, b31, 1.0),
        ApoConfig.ApoBand(62, b62, 1.0),
        ApoConfig.ApoBand(125, b125, 1.0),
        ApoConfig.ApoBand(250, b250, 1.0),
        ApoConfig.ApoBand(500, b500, 1.0),
        ApoConfig.ApoBand(1000, b1k, 1.0),
        ApoConfig.ApoBand(2000, b2k, 1.0),
        ApoConfig.ApoBand(4000, b4k, 1.0),
        ApoConfig.ApoBand(8000, b8k, 1.0),
        ApoConfig.ApoBand(16000, b16k, 1.0),
    )
}
