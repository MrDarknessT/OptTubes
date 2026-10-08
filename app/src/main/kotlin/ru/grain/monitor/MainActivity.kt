package ru.grain.monitor

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val producer = Executors.newSingleThreadScheduledExecutor()
    private var generationTask: ScheduledFuture<*>? = null
    @Volatile private var sessionId = 0
    private val events = ArrayDeque<String>()
    private lateinit var filter: SensorFilter
    private lateinit var simulator: Simulator
    private lateinit var scenario: Spinner
    private lateinit var mode: Spinner
    private lateinit var windowChoice: Spinner
    private lateinit var refresh: Spinner
    private lateinit var threshold: Spinner
    private lateinit var harvest: TextView
    private lateinit var summary: TextView
    private lateinit var timing: TextView
    private lateinit var details: TextView
    private lateinit var arrayText: TextView
    private lateinit var eventText: TextView
    private lateinit var explanation: TextView
    private lateinit var scenarioExplanation: TextView
    private lateinit var start: Button
    private lateinit var showRaw: CheckBox
    private lateinit var liveRaw: CheckBox
    private lateinit var filteredGrid: TubeGrid
    private lateinit var rawGrid: TubeGrid
    private var running = false
    private var initializing = true
    private var startedAt = 0L
    private var lastDraw = 0L
    private var frames = 0L
    private var lastPacket = -1L
    private var raw = IntArray(96) { -1 }
    private var displayed = IntArray(96) { -1 }
    private var selected = 0
    private val windows = longArrayOf(1000, 1500, 1800, 2000)
    private val refreshes = longArrayOf(1000, 1500, 2000)
    private val thresholds = intArrayOf(60, 70, 80, 90, 100)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val data = assets.open("excel_example.json").bufferedReader().use { JSONArray(it.readText()) }
        require(data.length() == 96)
        simulator = Simulator(IntArray(96) { data.getInt(it) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(body)
        body.addView(text("ЗЕРНО / 96", 27).apply { setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
        body.addView(text("Kotlin · вход: int[96] каждые 30 мс", 14))
        harvest = text("● Уборка окончена", 18).also(body::addView)
        start = Button(this).apply {
            text = "Начать уборку"
            setOnClickListener { if (running) stopHarvest() else beginHarvest() }
        }.also(body::addView)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val collapse = Button(this).apply {
            text = "Скрыть настройки и описание"
            setOnClickListener {
                val hide = controls.visibility == View.VISIBLE
                controls.visibility = if (hide) View.GONE else View.VISIBLE
                text = if (hide) "Показать настройки и описание" else "Скрыть настройки и описание"
            }
        }
        body.addView(collapse)
        body.addView(controls)
        scenario = spinner(controls, "Сценарий", Simulator.names, 0)
        scenarioExplanation = text("", 14).also(controls::addView)
        mode = spinner(controls, "Правило обработки", arrayOf("Зелёный обнуляет окно", "Скользящее окно · доля плохих"), 0)
        val settings = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(settings)
        windowChoice = spinner(column(settings), "Окно", arrayOf("1,0 с", "1,5 с", "1,8 с", "2,0 с"), 2)
        refresh = spinner(column(settings), "Экран", arrayOf("1,0 с", "1,5 с", "2,0 с"), 1)
        threshold = spinner(column(settings), "Порог", arrayOf("60%", "70%", "80%", "90%", "100%"), 1)
        explanation = text("", 13).also(controls::addView)
        summary = text("", 18).apply { setPadding(0, dp(12), 0, dp(8)) }.also(body::addView)
        timing = text("", 13).also(body::addView)
        body.addView(text("ПАНЕЛЬ ОПЕРАТОРА · нажмите на трубку", 14))
        filteredGrid = TubeGrid().also { body.addView(it, LinearLayout.LayoutParams(-1, dp(624))) }
        body.addView(text("1 — поток (зелёный)   0 — пусто   2 — забит   3 — отключён\nКрасный — подтверждённое состояние 0 / 2 / 3.\n… — накопление; ? — нет пакетов; — — контроль выключен.", 13))
        details = text("", 14).apply { setPadding(0, dp(12), 0, dp(12)) }.also(body::addView)
        showRaw = CheckBox(this).apply {
            text = "Показать исходные показания и массив"
            setTextColor(TEXT)
        }.also(body::addView)
        liveRaw = CheckBox(this).apply {
            text = "Исходная сетка каждые 30 мс · показать мерцание"
            setTextColor(TEXT)
        }.also(body::addView)
        rawGrid = TubeGrid().also { body.addView(it, LinearLayout.LayoutParams(-1, dp(624))) }
        arrayText = text("", 12).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }.also(body::addView)
        showRaw.setOnCheckedChangeListener { _, checked ->
            listOf(rawGrid, arrayText, liveRaw).forEach { it.visibility = if (checked) View.VISIBLE else View.GONE }
        }
        listOf(rawGrid, arrayText, liveRaw).forEach { it.visibility = View.GONE }
        body.addView(text("ПОСЛЕДНИЕ ИЗМЕНЕНИЯ ПАНЕЛИ", 14))
        eventText = text("", 13).also(body::addView)
        body.addView(Button(this).apply {
            text = "Сбросить ручные воздействия"
            setOnClickListener { synchronized(simulator) { simulator.reset() }; resetSession() }
        })
        setContentView(scroll)
        initializing = false
        resetSession()
    }

    private fun text(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(TEXT)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun column(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun spinner(parent: LinearLayout, label: String, choices: Array<String>, initial: Int): Spinner {
        parent.addView(text(label, 13))
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, choices).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(initial)
            parent.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!initializing && this@MainActivity::filter.isInitialized) resetSession()
                }
            }
        }
    }

    private fun cancelProducer() {
        sessionId++
        generationTask?.cancel(false)
        generationTask = null
    }

    private fun beginHarvest() {
        running = true
        synchronized(simulator) { simulator.reset() }
        resetSession()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopHarvest() {
        running = false
        cancelProducer()
        filter.reset()
        raw.fill(-1)
        displayed.fill(-1)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }

    private fun resetSession() {
        if (initializing) return
        cancelProducer()
        filter = SensorFilter(windows[windowChoice.selectedItemPosition], thresholds[threshold.selectedItemPosition],
            if (mode.selectedItemPosition == 0) SensorFilter.Mode.RESET_ON_GREEN else SensorFilter.Mode.ROLLING_PERCENT)
        startedAt = SystemClock.elapsedRealtime()
        lastDraw = startedAt
        frames = 0
        lastPacket = -1
        events.clear()
        raw.fill(-1)
        displayed.fill(-1)
        threshold.isEnabled = mode.selectedItemPosition == 1
        scenarioExplanation.text = Simulator.descriptions[scenario.selectedItemPosition]
        explanation.text = if (mode.selectedItemPosition == 0)
            "1 сразу даёт зелёный и очищает историю этой трубки. Красный — только после полного окна без 1. Порог доли здесь не применяется."
        else "История сохраняется при 1. После полного окна красный подтверждается при доле плохих ≥ порога либо непрерывном плохом потоке. 1 сразу даёт зелёный, но следующее плохое может вновь подтвердить тревогу."
        render()
        if (running) startProducer()
    }

    private fun startProducer() {
        val token = sessionId
        val scenarioId = scenario.selectedItemPosition
        val origin = startedAt
        var previousTimestamp = -1L
        // Generation has its own clock and thread; repainting does not set the input period.
        generationTask = producer.scheduleAtFixedRate({
            val now = SystemClock.elapsedRealtime()
            if (token != sessionId || now <= previousTimestamp) return@scheduleAtFixedRate
            previousTimestamp = now
            val packet = synchronized(simulator) {
                if (token != sessionId) return@scheduleAtFixedRate
                simulator.next(scenarioId, (now - origin) / SensorFilter.SAMPLE_MS)
            }
            handler.post { if (running && token == sessionId) processPacket(packet, now) }
        }, 0, SensorFilter.SAMPLE_MS, TimeUnit.MILLISECONDS)
    }

    /** Timestamp is captured by the producer, rather than at the next redraw. */
    private fun processPacket(packet: IntArray?, now: Long) {
        if (packet != null) {
            filter.accept(packet, now)
            raw = packet
            frames++
            lastPacket = now
        }
        filter.checkStale(now)
        val statuses = filter.statuses()
        var recovery = false
        val elapsed = now - startedAt
        statuses.forEachIndexed { i, status ->
            if (status == 1 && displayed[i] != 1) {
                addEvent(i, displayed[i], 1, elapsed)
                displayed[i] = 1
                recovery = true
            }
        }
        if (now - lastDraw >= refreshes[refresh.selectedItemPosition]) {
            statuses.forEachIndexed { i, status -> if (status != displayed[i]) addEvent(i, displayed[i], status, elapsed) }
            displayed = statuses
            lastDraw = now
            render()
        } else if (recovery) {
            filteredGrid.values = displayed.copyOf()
            filteredGrid.invalidate()
            updateSummary()
            updateDetails(now)
        }
        if (showRaw.isChecked && liveRaw.isChecked) {
            rawGrid.values = raw.copyOf()
            rawGrid.invalidate()
        }
    }

    private fun render() {
        harvest.text = if (running) "● Уборка начата" else "● Уборка окончена"
        harvest.setTextColor(if (running) GREEN else Color.LTGRAY)
        start.text = if (running) "Окончить уборку" else "Начать уборку"
        filteredGrid.values = displayed.copyOf()
        filteredGrid.invalidate()
        rawGrid.values = raw.copyOf()
        rawGrid.invalidate()
        updateSummary()
        val now = SystemClock.elapsedRealtime()
        val age = if (!running) "" else if (lastPacket < 0) " · пакетов ещё нет"
            else format(" · возраст пакета %.2f с", (now - lastPacket) / 1000.0)
        timing.text = format("Вход 30 мс · время %.1f с · пакетов %d\nОкно %d мс · экран %d мс%s",
            if (running) (now - startedAt) / 1000.0 else 0.0, frames,
            windows[windowChoice.selectedItemPosition], refreshes[refresh.selectedItemPosition], age)
        updateDetails(now)
        arrayText.text = "Последний полученный IntArray(96):\n${raw.contentToString()}"
        eventText.text = if (events.isEmpty()) "Изменений пока нет" else events.joinToString("\n")
    }

    private fun updateSummary() {
        summary.text = if (running) "Поток: ${displayed.count { it == 1 }}   Тревога: ${displayed.count { it >= 0 && it != 1 }}\n" +
            "Ожидание: ${displayed.count { it == -1 }}   Нет данных: ${displayed.count { it == -2 }}"
        else "96 трубок · контроль выключен"
    }

    private fun reason(d: SensorFilter.Diagnosis): String = when {
        !running -> "Контроль выключен; история очищена."
        d.status == SensorFilter.STALE -> "Пакетов нет дольше тайм-аута. Состояние трубки сейчас неизвестно."
        displayed[selected] != d.status -> "Фильтр уже подтвердил ${label(d.status)}; панель изменится при очередной отрисовке."
        d.raw == 1 -> "Получен рабочий пакет 1: зелёный сразу.${if (mode.selectedItemPosition == 0) " История очищена." else " История сохранена для оценки доли."}"
        d.status == 1 -> "Ранее был рабочий пакет 1. Текущая пауза ещё не подтверждает проблему; последний зелёный удерживается."
        d.status == SensorFilter.UNKNOWN -> "Ещё нет рабочего пакета, а полное окно не накоплено. Красный пока не подтверждён."
        d.failedForMs >= filter.windowMs -> "Полное окно без рабочих 1. Красный код ${d.status} — самое частое плохое состояние в окне."
        else -> "В полном окне ${format("%.0f", d.badFraction * 100)}% плохих пакетов, порог ${filter.thresholdPercent}%. Красный код ${d.status} выбран по частоте."
    }

    private fun updateDetails(now: Long) {
        val d = filter.diagnosis(selected, now)
        val override = synchronized(simulator) { simulator.overrideAt(selected) }
        details.text = format("Трубка №%d · вход: %s · панель: %s\nВ истории: %d · плохих: %.0f%% · без потока: %.2f с\nРучной режим: %s\nПочему: %s",
            selected + 1, label(raw[selected]), label(displayed[selected]), d.samples, d.badFraction * 100,
            d.failedForMs / 1000.0, if (override < 0) "авто" else label(override), reason(d))
    }

    private fun label(state: Int): String = when (state) {
        0 -> "0 · пусто"
        1 -> "1 · поток"
        2 -> "2 · забит"
        3 -> "3 · отключён"
        -2 -> "? · нет данных"
        else -> "… · ожидание"
    }

    private fun addEvent(i: Int, old: Int, state: Int, elapsed: Long) {
        events.addFirst(format("%5.2f с · №%02d · %s → %s", elapsed / 1000.0, i + 1, label(old), label(state)))
        while (events.size > 12) events.removeLast()
    }

    private fun inspect(i: Int) {
        selected = i
        render()
        AlertDialog.Builder(this).setTitle("Трубка №${i + 1}")
            .setMessage(null)
            .setItems(arrayOf("Автоматический сценарий", "Зафиксировать 0 · пусто", "Зафиксировать 1 · поток",
                "Зафиксировать 2 · забит", "Зафиксировать 3 · отключён", "Один пакет 1, затем авто")) { _, which ->
                synchronized(simulator) { if (which == 5) simulator.pulse(i) else simulator.override(i, which - 1) }
                render()
            }.setNegativeButton("Закрыть", null).show()
    }

    override fun onPause() { super.onPause(); if (running) stopHarvest() }
    override fun onDestroy() {
        cancelProducer()
        producer.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private inner class TubeGrid : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val numbers = Array(96) { format("%02d", it + 1) }
        var values = IntArray(96) { -1 }
        private var cols = 8
        private var cellWidth = 0f
        private var cellHeight = 0f

        init { contentDescription = "96 датчиков, номер и код. Нажмите для ручного воздействия." }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            cols = if (width / resources.displayMetrics.density >= 600) 12 else 8
            cellWidth = width.toFloat() / cols
            cellHeight = dp(52).toFloat()
            setMeasuredDimension(width, (ceil(96.0 / cols) * cellHeight).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            values.forEachIndexed { i, state ->
                val x = (i % cols) * cellWidth
                val y = (i / cols) * cellHeight
                val color = when { state == 1 -> GREEN; state >= 0 -> RED; state == -2 -> AMBER; else -> MUTED }
                rect.set(x + dp(2), y + dp(2), x + cellWidth - dp(2), y + cellHeight - dp(2))
                paint.color = PANEL
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), paint)
                if (i == selected) {
                    paint.color = Color.WHITE
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1).toFloat()
                    canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), paint)
                    paint.style = Paint.Style.FILL
                }
                paint.color = TEXT
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.DEFAULT
                paint.textSize = dp(12).toFloat()
                canvas.drawText(numbers[i], x + cellWidth / 2, y + dp(18), paint)
                paint.color = color
                canvas.drawCircle(x + cellWidth / 2 - dp(9), y + dp(35), dp(3).toFloat(), paint)
                paint.textSize = dp(15).toFloat()
                paint.typeface = Typeface.DEFAULT_BOLD
                val code = when { state >= 0 -> CODES[state]; state == -2 -> "?"; running -> "…"; else -> "—" }
                canvas.drawText(code, x + cellWidth / 2 + dp(4), y + dp(40), paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP && cellWidth > 0 && cellHeight > 0) {
                val i = (event.y / cellHeight).toInt() * cols + (event.x / cellWidth).toInt()
                if (i in 0 until 96) { performClick(); inspect(i) }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    companion object {
        private val BG = Color.rgb(12, 20, 31)
        private val PANEL = Color.rgb(23, 35, 49)
        private val TEXT = Color.rgb(235, 242, 249)
        private val GREEN = Color.rgb(68, 216, 157)
        private val RED = Color.rgb(255, 100, 110)
        private val AMBER = Color.rgb(250, 190, 72)
        private val MUTED = Color.rgb(115, 139, 164)
        private val CODES = arrayOf("0", "1", "2", "3")
        private fun format(pattern: String, vararg args: Any): String = String.format(Locale.ROOT, pattern, *args)
    }
}
