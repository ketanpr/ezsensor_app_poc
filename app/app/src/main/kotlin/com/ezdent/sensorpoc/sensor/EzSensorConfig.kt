package com.ezdent.sensorpoc.sensor

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Parser for EzSensor configuration files (EzSensor.ini).
 *
 * The sensor stores its configuration at:
 * - /sdcard/EzSensor/EzSensor.ini (default)
 * - /sdcard/EzSensor/MultiSensor/{serialId}/EzSensor.ini (per-sensor)
 *
 * The calibration data is stored at:
 * - /sdcard/EzSensor/CAL/BPM.raw (bad pixel map)
 * - /sdcard/EzSensor/CAL/dark.raw (dark frame reference)
 */
data class EzSensorConfig(
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val descramble: Int = 0,
    val vresetStepBase: Int = 0,
    val vreset: Int = 0,
    val binMode: Int = 0,
    val outMode: Int = 0,
    val gainMode: Int = 0,
    val pattern: Int = 0,
    val invert: Boolean = false,
    val imgCutLeft: Int = 0,
    val imgCutTop: Int = 0,
    val imgCutRight: Int = 0,
    val imgCutBottom: Int = 0,
    val rotation: Int = 0,
    val flip: Int = 0,           // 0=none, 1=horizontal, 2=vertical, 3=both
    val rcCutoffR: Int = 0,
    val rcCutoff: Int = 0,
    val exposureParam: Int = 0  // USB vendor command 0x50 wValue (default: 0x0225 from Wireshark)
) {
    /** Effective image width after crop */
    val effectiveWidth: Int
        get() = frameWidth - imgCutLeft - imgCutRight

    /** Effective image height after crop */
    val effectiveHeight: Int
        get() = frameHeight - imgCutTop - imgCutBottom

    companion object {
        private const val SECTION_SETTINGS = "[settings]"

        /**
         * Parse an EzSensor.ini file.
         */
        fun parse(file: File): EzSensorConfig {
            if (!file.exists()) return EzSensorConfig()

            val props = mutableMapOf<String, String>()
            var inSettings = false

            BufferedReader(FileReader(file)).use { reader ->
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    when {
                        trimmed.lowercase() == SECTION_SETTINGS -> inSettings = true
                        trimmed.startsWith("[") -> inSettings = false
                        inSettings && trimmed.contains("=") -> {
                            val (key, value) = trimmed.split("=", limit = 2)
                            props[key.trim().uppercase()] = value.trim()
                        }
                    }
                }
            }

            return EzSensorConfig(
                frameWidth = props["FRAMEWIDTH"]?.toIntOrNull() ?: 0,
                frameHeight = props["FRAMEHEIGHT"]?.toIntOrNull() ?: 0,
                descramble = props["DESCRAMBLE"]?.toIntOrNull() ?: 0,
                vresetStepBase = props["VRESETSTEPBASE"]?.toIntOrNull() ?: 0,
                vreset = props["VRESET"]?.toIntOrNull() ?: 0,
                binMode = props["BINMODE"]?.toIntOrNull() ?: 0,
                outMode = props["OUTMODE"]?.toIntOrNull() ?: 0,
                gainMode = props["GAINMODE"]?.toIntOrNull() ?: 0,
                pattern = props["PATTERN"]?.toIntOrNull() ?: 0,
                invert = (props["INVERT"]?.toIntOrNull() ?: 0) != 0,
                imgCutLeft = props["IMGCUTLEFT"]?.toIntOrNull() ?: 0,
                imgCutTop = props["IMGCUTTOP"]?.toIntOrNull() ?: 0,
                imgCutRight = props["IMGCUTRIGHT"]?.toIntOrNull() ?: 0,
                imgCutBottom = props["IMGCUTBOTTOM"]?.toIntOrNull() ?: 0,
                rotation = props["ROTATION"]?.toIntOrNull() ?: 0,
                flip = props["FLIP"]?.toIntOrNull() ?: 0,
                rcCutoffR = props["RCCUTOFFR"]?.toIntOrNull() ?: 0,
                rcCutoff = props["RCCUTOFF"]?.toIntOrNull() ?: 0,
                exposureParam = props["EXPOSUREPARAM"]?.toIntOrNull() ?: 0
            )
        }

        /**
         * Resolve the config file path for a given sensor serial ID.
         * Checks app-specific directories first, falls back to legacy storage.
         */
        fun resolveConfigPath(context: Context, serialId: String?): File {
            // 1. Check app-specific external files directory (primary)
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                val path = resolveConfigPathForBase(extDir.absolutePath, serialId)
                if (path.exists()) return path
            }

            // 2. Check app-specific internal files directory
            val intDir = context.filesDir
            val intPath = resolveConfigPathForBase(intDir.absolutePath, serialId)
            if (intPath.exists()) return intPath

            // 3. Check legacy external SDCard root
            val legacyBase = Environment.getExternalStorageDirectory().absolutePath
            val legacyPath = resolveConfigPathForBase(legacyBase, serialId)
            if (legacyPath.exists()) return legacyPath

            // Default fallback
            val defaultBase = extDir?.absolutePath ?: intDir.absolutePath
            return File("$defaultBase/EzSensor/EzSensor.ini")
        }

        private fun resolveConfigPathForBase(basePath: String, serialId: String?): File {
            if (serialId != null) {
                val perSensor = File("$basePath/EzSensor/MultiSensor/$serialId/EzSensor.ini")
                if (perSensor.exists()) return perSensor
            }
            return File("$basePath/EzSensor/EzSensor.ini")
        }

        /**
         * Resolve the calibration directory for a given sensor.
         */
        fun resolveCalibrationDir(context: Context, serialId: String?, calSet: Char = 'A'): File {
            // 1. Check app-specific external files directory
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                val dir = resolveCalibrationDirForBase(extDir.absolutePath, serialId, calSet)
                if (dir.exists() && hasCalibrationFiles(dir)) return dir
            }

            // 2. Check app-specific internal files directory
            val intDir = context.filesDir
            val intDirFile = resolveCalibrationDirForBase(intDir.absolutePath, serialId, calSet)
            if (intDirFile.exists() && hasCalibrationFiles(intDirFile)) return intDirFile

            // 3. Check legacy external SDCard root
            val legacyBase = Environment.getExternalStorageDirectory().absolutePath
            val legacyDir = resolveCalibrationDirForBase(legacyBase, serialId, calSet)
            if (legacyDir.exists() && hasCalibrationFiles(legacyDir)) return legacyDir

            // Fallback
            val defaultBase = extDir?.absolutePath ?: intDir.absolutePath
            if (serialId != null) {
                val perSensorCal = File("$defaultBase/EzSensor/MultiSensor/$serialId/CAL_$calSet")
                if (perSensorCal.exists()) return perSensorCal
                val perSensorRoot = File("$defaultBase/EzSensor/MultiSensor/$serialId")
                if (perSensorRoot.exists()) return perSensorRoot
            }
            return File("$defaultBase/EzSensor/CAL")
        }

        private fun hasCalibrationFiles(dir: File): Boolean {
            return File(dir, "dark.raw").exists() || File(dir, "BPM.raw").exists()
        }

        private fun resolveCalibrationDirForBase(basePath: String, serialId: String?, calSet: Char): File {
            if (serialId != null) {
                // First check if calibration files are directly in the multi-sensor folder (Vatech desktop style)
                val perSensorRoot = File("$basePath/EzSensor/MultiSensor/$serialId")
                if (File(perSensorRoot, "dark.raw").exists()) {
                    return perSensorRoot
                }

                // Then check per-sensor CAL_A/CAL
                val perSensor = File("$basePath/EzSensor/MultiSensor/$serialId/CAL_$calSet")
                if (perSensor.exists()) return perSensor
            }
            val defaultCal = File("$basePath/EzSensor/CAL_$calSet")
            if (defaultCal.exists()) return defaultCal
            return File("$basePath/EzSensor/CAL")
        }

        /**
         * Copy asset folder from APK assets to target directory if files do not exist.
         */
        fun copyAssetFolder(context: Context, srcFolder: String, destFolder: File) {
            val assetManager = context.assets
            val files = assetManager.list(srcFolder) ?: return
            if (files.isEmpty()) {
                val outFile = File(destFolder, srcFolder)
                if (!outFile.exists()) {
                    outFile.parentFile?.mkdirs()
                    try {
                        assetManager.open(srcFolder).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.i("EzSensorConfig", "Copied asset file: $srcFolder -> ${outFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.e("EzSensorConfig", "Failed to copy asset file $srcFolder", e)
                    }
                }
            } else {
                val dir = File(destFolder, srcFolder)
                dir.mkdirs()
                for (file in files) {
                    copyAssetFolder(context, "$srcFolder/$file", destFolder)
                }
            }
        }
    }
}
