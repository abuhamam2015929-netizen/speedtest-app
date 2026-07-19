package com.example.speedtestapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val viewModel: SpeedTestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpeedTestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SpeedTestScreen(viewModel = viewModel)
                }
            }
        }
    }
}

private val AppDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    secondary = Color(0xFF7C4DFF),
    tertiary = Color(0xFFFF4081),
    background = Color(0xFF0D0D14),
    surface = Color(0xFF1A1A24),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun SpeedTestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppDarkColorScheme,
        content = content
    )
}

enum class TestPhase { IDLE, PING, DOWNLOAD, UPLOAD, DONE }

data class SpeedTestUiState(
    val isTesting: Boolean = false,
    val currentPhase: TestPhase = TestPhase.IDLE,
    val currentSpeed: Double = 0.0,
    val pingResult: Long? = null,
    val downloadResult: Double? = null,
    val uploadResult: Double? = null
)

class SpeedTestViewModel : ViewModel() {

    var uiState by mutableStateOf(SpeedTestUiState())
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun startTest() {
        if (uiState.isTesting) return

        uiState = SpeedTestUiState(isTesting = true, currentPhase = TestPhase.PING)

        viewModelScope.launch {
            val ping = measurePing()
            uiState = uiState.copy(
                pingResult = ping,
                currentPhase = TestPhase.DOWNLOAD,
                currentSpeed = 0.0
            )

            val download = measureDownloadSpeed { liveSpeed ->
                uiState = uiState.copy(currentSpeed = liveSpeed)
            }
            uiState = uiState.copy(
                downloadResult = download,
                currentPhase = TestPhase.UPLOAD,
                currentSpeed = 0.0
            )

            val upload = measureUploadSpeed { liveSpeed ->
                uiState = uiState.copy(currentSpeed = liveSpeed)
            }
            uiState = uiState.copy(
                uploadResult = upload,
                currentPhase = TestPhase.DONE,
                isTesting = false,
                currentSpeed = 0.0
            )
        }
    }

    private suspend fun measurePing(): Long = withContext(Dispatchers.IO) {
        try {
            val times = mutableListOf<Long>()
            repeat(4) {
                val start = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("https://www.google.com")
                    .head()
                    .build()
                client.newCall(request).execute().close()
                times.add(System.currentTimeMillis() - start)
            }
            times.average().toLong()
        } catch (e: Exception) {
            -1L
        }
    }

    private suspend fun measureDownloadSpeed(onProgress: (Double) -> Unit): Double =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://speed.cloudflare.com/__down?bytes=25000000"
                val request = Request.Builder().url(url).build()

                client.newCall(request).execute().use { response ->
                    val body = response.body ?: return@withContext 0.0
                    val input = body.byteStream()
                    val buffer = ByteArray(8 * 1024)

                    var totalBytes = 0L
                    val startTime = System.nanoTime()
                    var lastReportTime = startTime
                    var lastReportBytes = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalBytes += bytesRead
                        val now = System.nanoTime()

                        if (now - lastReportTime > 200_000_000L) {
                            val intervalSeconds = (now - lastReportTime) / 1_000_000_000.0
                            val intervalBytes = totalBytes - lastReportBytes
                            val speedMbps = (intervalBytes * 8.0) / intervalSeconds / 1_000_000.0
                            onProgress(speedMbps)
                            lastReportTime = now
                            lastReportBytes = totalBytes
                        }
                    }

                    val totalSeconds = (System.nanoTime() - startTime) / 1_000_000_000.0
                    if (totalSeconds <= 0.0) 0.0
                    else (totalBytes * 8.0) / totalSeconds / 1_000_000.0
                }
            } catch (e: Exception) {
                0.0
            }
        }

    private suspend fun measureUploadSpeed(onProgress: (Double) -> Unit): Double =
        withContext(Dispatchers.IO) {
            try {
                val totalSize = 5 * 1024 * 1024
                val data = ByteArray(totalSize) { (it % 256).toByte() }
                val url = "https://speed.cloudflare.com/__up"

                val requestBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = totalSize.toLong()

                    override fun writeTo(sink: BufferedSink) {
                        val chunkSize = 8 * 1024
                        var offset = 0
                        var written = 0L
                        val startTime = System.nanoTime()
                        var lastReportTime = startTime
                        var lastReportBytes = 0L

                        while (offset < data.size) {
                            val len = minOf(chunkSize, data.size - offset)
                            sink.write(data, offset, len)
                            offset += len
                            written += len

                            val now = System.nanoTime()
                            if (now - lastReportTime > 200_000_000L) {
                                val intervalSeconds = (now - lastReportTime) / 1_000_000_000.0
                                val intervalBytes = written - lastReportBytes
                                val speedMbps = (intervalBytes * 8.0) / intervalSeconds / 1_000_000.0
                                onProgress(speedMbps)
                                lastReportTime = now
                                lastReportBytes = written
                            }
                        }
                    }
                }

                val request = Request.Builder().url(url).post(requestBody).build()
                val startTime = System.nanoTime()
                client.newCall(request).execute().close()
                val totalSeconds = (System.nanoTime() - startTime) / 1_000_000_000.0

                if (totalSeconds <= 0.0) 0.0
                else (totalSize * 8.0) / totalSeconds / 1_000_000.0
            } catch (e: Exception) {
                0.0
            }
        }
}

@Composable
fun SpeedTestScreen(viewModel: SpeedTestViewModel) {
    val state = viewModel.uiState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D14))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "فحص سرعة الإنترنت",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = when (state.currentPhase) {
                TestPhase.IDLE -> "اضغط على الزر لبدء الفحص"
                TestPhase.PING -> "جاري قياس البينغ..."
                TestPhase.DOWNLOAD -> "جاري قياس سرعة التحميل..."
                TestPhase.UPLOAD -> "جاري قياس سرعة الرفع..."
                TestPhase.DONE -> "اكتمل الفحص ✅"
            },
            fontSize = 14.sp,
            color = Color(0xFF9E9EAE)
        )

        Spacer(Modifier.height(24.dp))

        SpeedometerGauge(
            speed = state.currentSpeed,
            maxSpeed = 200.0,
            modifier = Modifier.padding(8.dp)
        )

        Spacer(Modifier.height(28.dp))

        StartTestButton(isTesting = state.isTesting) {
            viewModel.startTest()
        }

        Spacer(Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ResultCard(
                icon = Icons.Default.NetworkCheck,
                label = "Ping",
                value = state.pingResult?.let { if (it < 0) "خطأ" else it.toString() } ?: "--",
                unit = "ms",
                modifier = Modifier.weight(1f)
            )
            ResultCard(
                icon = Icons.Default.CloudDownload,
                label = "Download",
                value = state.downloadResult?.let { String.format("%.1f", it) } ?: "--",
                unit = "Mbps",
                modifier = Modifier.weight(1f)
            )
            ResultCard(
                icon = Icons.Default.CloudUpload,
                label = "Upload",
                value = state.uploadResult?.let { String.format("%.1f", it) } ?: "--",
                unit = "Mbps",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun SpeedometerGauge(
    speed: Double,
    maxSpeed: Double = 200.0,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speed.toFloat(),
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "speedAnimation"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(260.dp)) {
            val strokeWidth = 22.dp.toPx()
            val radius = size.minDimension / 2 - strokeWidth
            val center = Offset(size.width / 2, size.height / 2)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)

            drawArc(
                color = Color(0xFF2A2A38),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = topLeft,
                size = arcSize
            )

            val progress = (animatedSpeed / maxSpeed.toFloat()).coerceIn(0f, 1f)
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF), Color(0xFFFF4081))
                ),
                startAngle = 135f,
                sweepAngle = 270f * progress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = topLeft,
                size = arcSize
            )

            val needleAngleDeg = 135f + 270f * progress
            val needleAngleRad = Math.toRadians(needleAngleDeg.toDouble())
            val needleLength = radius - 6.dp.toPx()
            val needleEnd = Offset(
                x = center.x + (needleLength * cos(needleAngleRad)).toFloat(),
                y = center.y + (needleLength * sin(needleAngleRad)).toFloat()
            )
            drawLine(
                color = Color.White,
                start = center,
                end = needleEnd,
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(color = Color.White, radius = 7.dp.toPx(), center = center)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.1f", animatedSpeed),
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(text = "Mbps", fontSize = 13.sp, color = Color(0xFF9E9EAE))
        }
    }
}

@Composable
fun StartTestButton(isTesting: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (isTesting) 0.92f else 1f,
        animationSpec = tween(300),
        label = "buttonScale"
    )

    Box(
        modifier = Modifier
            .size(96.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF))))
            .clickable(enabled = !isTesting) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isTesting) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp)
            )
        } else {
            Text(
                text = "Start\nTest",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun ResultCard(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 16.dp, horizontal = 8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = label, tint = Color(0xFF00E5FF))
            Spacer(Modifier.height(6.dp))
            Text(text = label, fontSize = 11.sp, color = Color(0xFF9E9EAE))
            Text(text = value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text = unit, fontSize = 10.sp, color = Color(0xFF9E9EAE))
        }
    }
}
