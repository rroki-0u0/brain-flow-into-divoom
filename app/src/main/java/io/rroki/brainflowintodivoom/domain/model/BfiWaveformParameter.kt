package io.rroki.brainflowintodivoom.domain.model

enum class BfiWaveformParameter(
    val label: String,
    val oscPath: String,
    val requiresOptics: Boolean,
    val unit: String,
    val colorArgb: Int
) {
    PWR_AVG_DELTA(
        label = "Delta (Avg)",
        oscPath = "BFI/PwrBands/Avg/Delta",
        requiresOptics = false,
        unit = "ratio",
        colorArgb = 0xFF38BDF8.toInt()
    ),
    PWR_AVG_THETA(
        label = "Theta (Avg)",
        oscPath = "BFI/PwrBands/Avg/Theta",
        requiresOptics = false,
        unit = "ratio",
        colorArgb = 0xFF60A5FA.toInt()
    ),
    PWR_AVG_ALPHA(
        label = "Alpha (Avg)",
        oscPath = "BFI/PwrBands/Avg/Alpha",
        requiresOptics = false,
        unit = "ratio",
        colorArgb = 0xFF22C55E.toInt()
    ),
    PWR_AVG_BETA(
        label = "Beta (Avg)",
        oscPath = "BFI/PwrBands/Avg/Beta",
        requiresOptics = false,
        unit = "ratio",
        colorArgb = 0xFFEF4444.toInt()
    ),
    PWR_AVG_GAMMA(
        label = "Gamma (Avg)",
        oscPath = "BFI/PwrBands/Avg/Gamma",
        requiresOptics = false,
        unit = "ratio",
        colorArgb = 0xFFF59E0B.toInt()
    ),
    NEUROFB_FOCUS_AVG(
        label = "Focus (Avg)",
        oscPath = "BFI/NeuroFB/FocusAvgPos",
        requiresOptics = false,
        unit = "score",
        colorArgb = 0xFFF97316.toInt()
    ),
    NEUROFB_RELAX_AVG(
        label = "Relax (Avg)",
        oscPath = "BFI/NeuroFB/RelaxAvgPos",
        requiresOptics = false,
        unit = "score",
        colorArgb = 0xFF84CC16.toInt()
    ),
    BIOMETRICS_HEART_BPM(
        label = "Heart Rate",
        oscPath = "BFI/Biometrics/HeartBeatsPerMinute",
        requiresOptics = true,
        unit = "bpm",
        colorArgb = 0xFFF43F5E.toInt()
    ),
    BIOMETRICS_OXYGEN(
        label = "Oxygen Proxy",
        oscPath = "BFI/Biometrics/OxygenPercent",
        requiresOptics = true,
        unit = "percent",
        colorArgb = 0xFF06B6D4.toInt()
    )
}

enum class MusePowerMode(
    val label: String,
    val description: String
) {
    AUTO(
        label = "Auto",
        description = "Enable only the sensors required by the selected parameter"
    ),
    EEG_ONLY(
        label = "EEG Only",
        description = "Use EEG only to prioritize battery life"
    ),
    FULL_BIOMETRICS(
        label = "Full",
        description = "Enable EEG + PPG/Optics"
    )
}
