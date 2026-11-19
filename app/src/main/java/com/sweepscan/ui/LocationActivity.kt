package com.sweepscan.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.sweepscan.R
import com.sweepscan.SweepScanApp
import com.sweepscan.location.Face
import com.sweepscan.location.LabelSheet
import com.sweepscan.location.LocationCode
import com.sweepscan.location.PickItem
import com.sweepscan.location.RouteFinder
import com.sweepscan.location.RowPosition
import com.sweepscan.location.SightingWithModel
import com.sweepscan.location.Sweep
import com.sweepscan.location.SweepDiff
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Where is it?" A full-size plan under a search bar, with a bottom sheet in four modes:
 *
 * - **Overview** (nothing typed): how fresh the map is, the columns to sweep next, the last sweeps.
 * - **Search**: a model code (or part of it), a barcode; every column holding it is amber, the
 *   nearest one is chosen and the walking route drawn.
 * - **Column**: one column's contents (top to bottom), its sweep history with what changed, undo.
 * - **Pick list**: stops gathered from searches, in the shortest walking order, numbered.
 *
 * From any of them "Kamerada bul" opens the camera, which marks the wanted boxes.
 */
class LocationActivity : AppCompatActivity() {

    private enum class Mode { OVERVIEW, SEARCH, COLUMN, PICK }

    private val app get() = application as SweepScanApp
    private val store get() = app.locations

    private lateinit var query: EditText
    private lateinit var plan: PlanView
    private lateinit var front: FrontView
    private lateinit var frontTitle: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var actions: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var depots: ChipGroup
    private lateinit var pickChip: Chip
    private lateinit var sheet: BottomSheetBehavior<View>
    private lateinit var reopen: com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

    private val handler = Handler(Looper.getMainLooper())
    private val searchLater = Runnable { search() }
    private var mode = Mode.OVERVIEW
    /** Where Back from a column returns to. */
    private var before = Mode.OVERVIEW
    private var editMode = false
    private var found: List<SightingWithModel> = emptyList()
    private val tr = Locale.forLanguageTag("tr")

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            Toast.makeText(this, R.string.loc_import_failed, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        // Excel on a Turkish Windows saves CSV as Windows-1254 unless "UTF-8 CSV" is chosen.
        var text = String(bytes, Charsets.UTF_8)
        if ('�' in text) text = String(bytes, Charset.forName("windows-1254"))
        store.importModels(text.lineSequence()) { (ok, skipped) ->
            Toast.makeText(this, getString(R.string.loc_import_done, ok, skipped), Toast.LENGTH_LONG).show()
            search()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location)
        SystemBars.pad(findViewById(R.id.loc_top), top = true)
        SystemBars.pad(findViewById(R.id.loc_sheet_body), bottom = true)
        query = findViewById(R.id.loc_query)
        plan = findViewById(R.id.loc_plan)
        front = findViewById(R.id.loc_front)
        frontTitle = findViewById(R.id.loc_front_title)
        title = findViewById(R.id.loc_title)
        subtitle = findViewById(R.id.loc_subtitle)
        actions = findViewById(R.id.loc_actions)
        results = findViewById(R.id.loc_results)
        depots = findViewById(R.id.loc_depots)
        pickChip = findViewById(R.id.loc_pick_chip)

        sheet = BottomSheetBehavior.from(findViewById(R.id.loc_sheet))
        sheet.state = BottomSheetBehavior.STATE_COLLAPSED
        plan.bottomInset = sheet.peekHeight.toFloat()
        reopen = findViewById(R.id.loc_reopen)
        // Above the navigation bar: its height goes into the margin, not into the button.
        val reopenMargin = (reopen.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        // The collapsed sheet also gets the navigation bar's height, so its buttons sit above
        // the gesture handle instead of under it.
        val peek = sheet.peekHeight
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(reopen) { v, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = reopenMargin + nav
            v.requestLayout()
            if (sheet.peekHeight != peek + nav) {
                sheet.peekHeight = peek + nav
                plan.bottomInset = sheet.peekHeight.toFloat()
            }
            insets
        }
        reopen.setOnClickListener { openSheet() }
        findViewById<View>(R.id.loc_close).setOnClickListener { sheet.state = BottomSheetBehavior.STATE_HIDDEN }
        // The map is what stays above the sheet: when the sheet settles higher or lower, the
        // found place is brought back into that part. Pushed all the way down, the sheet hides
        // and a small button brings it back.
        sheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(v: View, state: Int) {
                when (state) {
                    BottomSheetBehavior.STATE_COLLAPSED, BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                        reopen.hide()
                        fitMapAboveSheet(v)
                    }
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        reopen.visibility = View.VISIBLE
                        reopen.show()
                        fitMapAboveSheet(null)
                    }
                    BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_EXPANDED -> reopen.hide()
                }
            }
            override fun onSlide(v: View, offset: Float) = Unit
        })

        findViewById<View>(R.id.loc_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.loc_menu).setOnClickListener { showMenu(it) }
        pickChip.setOnClickListener { showPick() }
        plan.layout = store.layout
        plan.onTap = { depot, row, col -> onPlanTap(depot, row, col) }
        front.onTap = { col ->
            val r = front.row
            val d = plan.selectedRow?.first
            if (r != null && d != null) showColumn(LocationCode(d, r.name.uppercase(), col))
        }
        buildDepotChips()
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchLater)
                handler.postDelayed(searchLater, 250)
            }
        })
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search()
                hideKeyboard()
                true
            } else {
                false
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        findViewById<View>(R.id.loc_demo_banner).visibility = if (store.demo && !com.sweepscan.request.Presentation.isOn(this)) View.VISIBLE else View.GONE
        intent.getStringExtra(EXTRA_QUERY)?.let { query.setText(it) }
        search()
        // Opened from the settings screen for one of the tools.
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_EDIT -> toggleEdit()
            ACTION_LABELS -> askLabels()
            ACTION_IMPORT -> launchImport()
            ACTION_EXPORT -> store.exportCsv(shareDir()) { file -> share(file, "text/csv") }
            ACTION_PICK -> showPick()
        }
        if (intent.getBooleanExtra(EXTRA_FOCUS, false)) {
            query.requestFocus()
            query.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCoverage()
        refreshPickChip()
    }

    /** Brings a closed (hidden) or low sheet up to half height. */
    private fun openSheet() {
        val s = sheet.state
        if (s == BottomSheetBehavior.STATE_HIDDEN || s == BottomSheetBehavior.STATE_COLLAPSED) sheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
    }

    /** Shows a hidden sheet again at its low height (new results), without covering more. */
    private fun peekSheet() {
        if (sheet.state == BottomSheetBehavior.STATE_HIDDEN) sheet.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    /**
     * Sets the map's covered bottom to where [sheetView] now starts (null: the sheet is hidden),
     * and keeps the target in view.
     */
    private fun fitMapAboveSheet(sheetView: View?) {
        val map = plan.parent as View
        val inset = if (sheetView == null) {
            0f
        } else {
            val m = IntArray(2).also { map.getLocationInWindow(it) }
            val s = IntArray(2).also { sheetView.getLocationInWindow(it) }
            (m[1] + map.height - s[1]).toFloat().coerceIn(0f, map.height * 0.8f)
        }
        if (kotlin.math.abs(inset - plan.bottomInset) < 2f) return
        plan.bottomInset = inset
        plan.fit()
        plan.selected?.let { plan.focus(it) }
    }

    private fun goBack() {
        when {
            mode == Mode.COLUMN -> if (before == Mode.PICK) showPick() else search()
            mode == Mode.PICK -> search()
            query.text?.isNotEmpty() == true -> query.setText("")
            else -> finish()
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()
    }

    // ---- Depots, coverage, pick list chip ----------------------------------------------------

    private fun buildDepotChips() {
        depots.removeAllViews()
        val all = listOf<Pair<String?, String>>(null to getString(R.string.loc_all_depots)) + store.layout.depots.map { it.code to it.name }
        for ((code, name) in all) {
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = name
                isCheckable = true
                isChecked = code == plan.depotFilter
                tag = code
                id = View.generateViewId()
            }
            depots.addView(chip)
        }
        depots.setOnCheckedStateChangeListener { group, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            val code = chip.tag as String?
            if (code != plan.depotFilter) {
                plan.depotFilter = code
                plan.post { plan.refit() }
            }
        }
    }

    private fun checkDepotChip(code: String?) {
        for (i in 0 until depots.childCount) {
            val c = depots.getChildAt(i) as Chip
            if (c.tag == code) c.isChecked = true
        }
    }

    /** Shades every column by the age of its last sweep (status mode). */
    private fun refreshCoverage() {
        store.query({ it.scans() }) { scans ->
            val now = System.currentTimeMillis()
            plan.scanned = scans.associate {
                LocationCode(it.depot, it.row, it.col) to if (now - it.scannedAt < FRESH_MS) PlanView.Age.FRESH else PlanView.Age.OLD
            }
        }
    }

    private fun refreshPickChip() {
        val n = app.pickList.size
        pickChip.visibility = if (n == 0) View.GONE else View.VISIBLE
        pickChip.text = getString(R.string.pick_chip, n)
    }

    // ---- Search ------------------------------------------------------------------------------

    private fun search() {
        val text = query.text?.toString()?.trim().orEmpty()
        val digits = text.filter { it.isDigit() }
        val location = LocationCode.parseText(text)?.takeIf { store.layout.contains(it) }
        when {
            text.isEmpty() -> showOverview()
            location != null -> showColumn(location)
            digits.length < 3 -> {
                mode = Mode.SEARCH
                clearTargets()
                title.setText(R.string.loc_type_more_title)
                subtitle.setText(R.string.loc_type_more)
                actions.removeAllViews()
                results.removeAllViews()
                showFront(false)
                peekSheet()
            }
            else -> store.query({ dao ->
                // A box's barcode: show its whole model (every size), with that size chosen.
                val model = if (digits.length == 8 || digits.length == 13) dao.model(digits) else null
                val rows = when {
                    model != null -> dao.findByModel(model.modelDigits)
                    digits.length == 8 || digits.length == 13 -> dao.findByBarcode(digits).ifEmpty { dao.findByModel(digits) }
                    else -> dao.findByModel(digits)
                }
                val contents = rows.map { it.code }.distinct().associateWith { c -> dao.column(c.depot, c.row, c.column) }
                Triple(rows, contents, model?.size)
            }) { (rows, contents, size) -> showSearch(rows, contents, size) }
        }
    }

    private fun clearTargets() {
        found = emptyList()
        plan.markers = emptyMap()
        plan.shelves = emptyMap()
        front.marker = null
        plan.highlighted = emptySet()
        plan.route = null
        plan.stops = emptyList()
        plan.selected = null
        plan.selectedRow = null
    }

    // ---- Overview ----------------------------------------------------------------------------

    private fun showOverview() {
        mode = Mode.OVERVIEW
        clearTargets()
        plan.statusMode = true
        showFront(false)
        title.setText(if (editMode) R.string.loc_edit else R.string.ov_title)
        val layout = store.layout
        val all = layout.depots.flatMap { it.codes() }
        store.query({ dao -> dao.scans() to dao.recentSweeps(6) }) { (scans, recent) ->
            if (mode != Mode.OVERVIEW) return@query
            val now = System.currentTimeMillis()
            val last = scans.associate { LocationCode(it.depot, it.row, it.col) to it.scannedAt }
            val old = last.count { now - it.value >= FRESH_MS }
            val never = all.count { it !in last }
            subtitle.text = if (editMode) getString(R.string.loc_edit_hint) else getString(R.string.ov_subtitle, last.size, all.size, old, never)
            // Next to sweep: never swept first, then the oldest; nearest first within each.
            val distance = HashMap<LocationCode, Float>()
            fun dist(c: LocationCode) = distance.getOrPut(c) { RouteFinder.route(layout, c)?.length ?: Float.MAX_VALUE }
            val next = all.sortedWith(compareBy<LocationCode>({ last[it] ?: 0L }, { dist(it) })).take(NEXT_COUNT)

            actions.removeAllViews()
            next.firstOrNull()?.let { first ->
                addAction(R.string.ov_go_next, R.drawable.ic_route, primary = true) { showColumn(first) }
            }
            addAction(R.string.loc_labels, R.drawable.ic_print) { askLabels() }
            addAction(R.string.loc_edit, R.drawable.ic_settings) { toggleEdit() }

            results.removeAllViews()
            if (scans.isEmpty()) results.addView(emptyMapCard())
            results.addView(section(getString(R.string.ov_next)))
            for (c in next) {
                val seen = last[c]
                results.addView(row(layout.short(c), layout.describe(c), null, if (seen == null) getString(R.string.ov_never) else ago(seen), codeColor = if (seen == null) 0xFFFFFFFF.toInt() else 0xFFB0B8C1.toInt()) { showColumn(c) })
            }
            if (recent.isNotEmpty()) {
                results.addView(section(getString(R.string.ov_recent)))
                for (s in recent) {
                    results.addView(row(layout.short(s.code), layout.describe(s.code), null, ago(s.finishedAt)) { showColumn(s.code) })
                }
            }
            results.addView(note(getString(R.string.ov_legend)))
        }
    }

    // ---- Search results ----------------------------------------------------------------------

    private var allRows: List<SightingWithModel> = emptyList()
    private var rowContents: Map<LocationCode, List<SightingWithModel>> = emptyMap()
    /** The size chip chosen (null = every size). */
    private var sizeFilter: String? = null

    private fun showSearch(rows: List<SightingWithModel>, contents: Map<LocationCode, List<SightingWithModel>> = emptyMap(), size: String? = null) {
        allRows = rows
        rowContents = contents
        sizeFilter = size?.takeIf { s -> rows.any { it.size == s } }
        if (rows.isNotEmpty()) {
            val layout = store.layout
            val nearest = rows.map { it.code }.distinct().minByOrNull { RouteFinder.route(layout, it)?.length ?: Float.MAX_VALUE }
            HomeActivity.addRecent(this, query.text?.toString().orEmpty(), nearest?.let { layout.short(it) })
        }
        renderSearch(null)
    }

    /** The answer first: the chosen (by default the nearest) place in a big card, the rest below. */
    private fun renderSearch(chosen: LocationCode?) {
        mode = Mode.SEARCH
        plan.statusMode = false
        plan.stops = emptyList()
        plan.markers = emptyMap()
        plan.shelves = emptyMap()
        val rows = sizeFilter?.let { f -> allRows.filter { it.size == f } } ?: allRows
        found = rows
        results.removeAllViews()
        actions.removeAllViews()
        showFront(false)
        if (allRows.isEmpty()) {
            clearTargets()
            title.setText(R.string.loc_not_found_title)
            subtitle.setText(R.string.loc_not_found)
            addAction(R.string.home_rows, R.drawable.ic_rows, primary = true) { startActivity(Intent(this, RowListActivity::class.java)) }
            addAction(R.string.loc_import, R.drawable.ic_add) { launchImport() }
            val digits = query.text?.toString().orEmpty().filter { it.isDigit() }
            store.query({ dao -> dao.unmappedCount() to (if (digits.length >= 6) dao.similarModels(digits.take(4)) else emptyList()) }) { (n, similar) ->
                if (mode != Mode.SEARCH || allRows.isNotEmpty()) return@query
                if (similar.isNotEmpty()) {
                    results.addView(section(getString(R.string.similar_codes)))
                    results.addView(ChipGroup(this).apply {
                        similar.forEach { m ->
                            addView(Chip(this@LocationActivity).apply {
                                text = m
                                setOnClickListener { query.setText(m) }
                            })
                        }
                    })
                }
                if (n > 0) results.addView(note(getString(R.string.loc_unmapped, n)))
            }
            return
        }
        val layout = store.layout
        val codes = rows.map { it.code }.toSet()
        plan.highlighted = codes
        val distance = codes.associateWith { RouteFinder.route(layout, it)?.length ?: Float.MAX_VALUE }
        val nearest = codes.minByOrNull { distance.getValue(it) }
        val target = chosen?.takeIf { it in codes } ?: nearest
        val models = allRows.groupBy { modelName(it) }
        // Where along each row: the found boxes' place in that row's sweep, and which model from
        // the left they are.
        val along = HashMap<LocationCode, Float>()
        val place = HashMap<LocationCode, String>()
        for ((code, items) in rows.groupBy { it.code }) {
            val f = RowPosition.fraction(items.minOf { it.firstOrder }, items.maxOf { maxOf(it.lastOrder, it.firstOrder) }, items.first().total ?: 0)
            val row = rowContents[code].orEmpty()
            val ordinals = RowPosition.modelOrdinals(row.map { Triple(it.barcode, modelKey(it), it.firstOrder) })
            val n = items.mapNotNull { ordinals[it.barcode] }.minOrNull()
            place[code] = zoneText(f) + (if (n != null && n > 0) " · " + getString(R.string.pos_nth_model, n) else "")
            if (layout.wholeRow(code)) along[code] = f
        }
        plan.markers = along
        plan.shelves = rows.groupBy { it.code }.mapNotNull { (c, items) -> app.shelf.level(items.map { it.barcode }, c)?.let { c to it } }.toMap()
        title.text = if (models.size == 1) models.keys.first() else getString(R.string.loc_models_found, models.size)
        subtitle.text = if (target == null) getString(R.string.size_none, sizeFilter)
        else getString(R.string.loc_found_where, codes.size, layout.short(target))

        if (codes.size > 1) addAction(R.string.pick_route_all, R.drawable.ic_route) { addToPick(rows); showPick() }
        addAction(R.string.pick_add, R.drawable.ic_add) {
            addToPick(rows)
            Toast.makeText(this, getString(R.string.pick_added, codes.size), Toast.LENGTH_SHORT).show()
        }

        // Size chips: "39 numara nerede?" is one tap.
        val sizes = allRows.mapNotNull { it.size }.distinct().sortedBy { it.toFloatOrNull() ?: 999f }
        if (sizes.size > 1) results.addView(sizeChips(sizes))

        if (target != null) {
            val items = rows.filter { it.code == target }
            results.addView(answerCard(target, items, place[target], along[target]))
        }
        val others = rows.filter { it.code != target }.groupBy { it.code }.toList().sortedBy { (code, _) -> distance[code] ?: Float.MAX_VALUE }
        if (others.isNotEmpty()) results.addView(section(getString(R.string.answer_other_places, others.size)))
        for ((code, items) in others) {
            val seen = items.maxOf { it.seenAt }
            val where = listOfNotNull(layout.describe(code), place[code]).joinToString(" · ")
            results.addView(row(layout.short(code), sizesText(items), where, ago(seen), codeColor = PlanView.TARGET, stale = isOld(seen)) {
                renderSearch(code)
            })
        }
        if (target != null) selectTarget(target)
        sheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
    }

    /** Nothing swept yet: what to do first. */
    private fun emptyMapCard(): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_answer, results, false)
        v.findViewById<TextView>(R.id.answer_where).setText(R.string.empty_map_title)
        v.findViewById<TextView>(R.id.answer_pos).setText(R.string.empty_map_body)
        v.findViewById<View>(R.id.answer_sizes).visibility = View.GONE
        v.findViewById<View>(R.id.answer_strip).visibility = View.GONE
        val buttons = v.findViewById<LinearLayout>(R.id.answer_actions)
        val first = LayoutInflater.from(this).inflate(R.layout.item_action_primary, buttons, false) as MaterialButton
        first.setText(R.string.home_rows)
        first.setIconResource(R.drawable.ic_rows)
        first.setOnClickListener { startActivity(Intent(this, RowListActivity::class.java)) }
        buttons.addView(first)
        val demo = LayoutInflater.from(this).inflate(R.layout.item_action, buttons, false) as MaterialButton
        demo.setText(R.string.demo_on)
        demo.setIconResource(R.drawable.ic_play)
        demo.setOnClickListener { toggleDemo() }
        buttons.addView(demo)
        return v
    }

    /** The sizes seen there ("38 · 39 · 40"): rows are skimmed, so box counts mean nothing. */
    private fun sizesText(items: List<SightingWithModel>): String {
        val sizes = items.mapNotNull { it.size }.distinct().sortedBy { it.toFloatOrNull() ?: 999f }
        return if (sizes.isEmpty()) getString(R.string.sizes_unknown) else getString(R.string.sizes_seen, sizes.joinToString(" · "))
    }

    private fun sizeChips(sizes: List<String>): View {
        val group = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isSingleLine = true
        }
        fun add(label: String, value: String?) {
            group.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = label
                isCheckable = true
                isChecked = value == sizeFilter
                setOnClickListener {
                    if (sizeFilter != value) {
                        sizeFilter = value
                        renderSearch(null)
                    }
                }
            })
        }
        add(getString(R.string.size_all), null)
        sizes.forEach { add(it, it) }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
    }

    /** The big answer: depot and row, where along it, sizes, a strip of the row, Rota and Kamerada bul. */
    private fun answerCard(code: LocationCode, items: List<SightingWithModel>, where: String?, along: Float?): View {
        val layout = store.layout
        val v = LayoutInflater.from(this).inflate(R.layout.item_answer, results, false)
        v.findViewById<TextView>(R.id.answer_where).text = layout.describe(code)
        val barcodes = items.map { it.barcode }
        val shelfNo = app.shelf.level(barcodes, code)?.takeIf { (layout.rowOf(code)?.levels ?: 1) > 1 }
        val pos = listOfNotNull(where?.takeIf { it.isNotEmpty() }, shelfNo?.let { getString(R.string.answer_level, it) }).joinToString(" · ")
        v.findViewById<TextView>(R.id.answer_pos).apply {
            text = pos
            visibility = if (pos.isEmpty()) View.GONE else View.VISIBLE
        }
        // The flight to the shelf in 3D; built with the shelf photo when there is one.
        val scene3d = sceneCard(v, code, along, shelfNo)
        val latestShot = app.shelf.latest(barcodes, code)
        if (latestShot == null) scene3d?.invoke(null)
        latestShot?.let { shot -> app.shelf.decode(shot) { bmp ->
            scene3d?.invoke(bmp)
            if (bmp == null || isDestroyed) return@decode
            v.findViewById<View>(R.id.answer_photo_box).visibility = View.VISIBLE
            v.findViewById<android.widget.ImageView>(R.id.answer_photo).apply {
                setImageBitmap(bmp)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, 12f * resources.displayMetrics.density)
                }
                setOnClickListener { showPhoto(bmp, layout.describe(code), shot.time) }
            }
            v.findViewById<TextView>(R.id.answer_photo_caption).text = getString(R.string.answer_photo_seen, ago(shot.time))
        } }
        val seen = items.maxOf { it.seenAt }
        v.findViewById<TextView>(R.id.answer_sizes).text = sizesText(items) + " · " + getString(R.string.answer_seen, ago(seen))
        v.findViewById<FrontView>(R.id.answer_strip).apply {
            row = layout.rowOf(code)
            marker = along
            shelfLevel = shelfNo
            selected = if (layout.wholeRow(code)) null else code.column
            highlighted = setOf(code.column)
            endLabels = getString(R.string.pos_left_end) to getString(R.string.pos_right_end)
        }
        val buttons = v.findViewById<LinearLayout>(R.id.answer_actions)
        fun button(text: Int, icon: Int, primary: Boolean, onClick: () -> Unit) {
            val b = LayoutInflater.from(this).inflate(if (primary) R.layout.item_action_primary else R.layout.item_action, buttons, false) as MaterialButton
            b.setText(text)
            b.setIconResource(icon)
            b.setOnClickListener { onClick() }
            buttons.addView(b)
        }
        button(R.string.answer_route, R.drawable.ic_route, true) {
            selectTarget(code)
            sheet.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        button(R.string.find_in_camera, R.drawable.ic_camera, false) {
            findInCamera(items.map { it.barcode }.toSet(), title.text.toString())
        }
        // Opened for a request: finish it here, without going back to the queue.
        val request = intent.getLongExtra(EXTRA_REQUEST, 0L)
        val open = request != 0L && com.sweepscan.request.Requests.recent(this).any { it.id == request && it.status.open }
        if (open) {
            buttons.removeAllViews()
            button(R.string.req_sent, R.drawable.ic_done, true) { finishRequest(request, com.sweepscan.request.Requests.Status.SENT) }
            button(R.string.req_missing, R.drawable.ic_undo, false) { finishRequest(request, com.sweepscan.request.Requests.Status.MISSING) }
            button(R.string.answer_route, R.drawable.ic_route, false) {
                selectTarget(code)
                sheet.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        } else {
            button(R.string.not_there, R.drawable.ic_undo, false) {
                com.sweepscan.location.RowFlags.flag(this, code, items.first().model)
                Toast.makeText(this, getString(R.string.not_there_done, layout.short(code)), Toast.LENGTH_LONG).show()
            }
        }
        v.setOnClickListener { showColumn(code) }
        return v
    }

    /** The shelf photo, large. */
    private fun showPhoto(bmp: android.graphics.Bitmap, title: String, time: Long) {
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setBackgroundColor(0xFF000000.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(getString(R.string.answer_photo_seen, ago(time)))
            .setView(image)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Adds the 3D flight to an answer card [card]; returns the function that builds it (with the
     * shelf photo, or null) off the main thread, or null where 3D cannot run.
     */
    private fun sceneCard(card: View, code: LocationCode, along: Float?, shelf: Int?): ((android.graphics.Bitmap?) -> Unit)? {
        if (!com.sweepscan.twin.ShelfSceneView.supported(this) || !com.sweepscan.twin.ShelfScene.init()) return null
        val box = card.findViewById<android.widget.FrameLayout>(R.id.answer_scene_box)
        box.visibility = View.VISIBLE
        val view = com.sweepscan.twin.ShelfSceneView(this)
        view.onFirstFrame = { card.findViewById<View>(R.id.answer_scene_wait).visibility = View.GONE }
        box.addView(view, 0, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        val layout = store.layout
        var started = false
        return { photo ->
            if (!started) {
                started = true
                Thread {
                    val prepared = runCatching { com.sweepscan.twin.ShelfShot.prepare(layout, code, along, shelf, photo) }.getOrNull()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (prepared == null) box.visibility = View.GONE else view.show(prepared)
                    }
                }.start()
            }
        }
    }

    /** Chooses one of the search hits: ring, route, front view; the sheet stays in search mode. */
    private fun selectTarget(code: LocationCode) {
        val layout = store.layout
        val row = layout.rowOf(code) ?: return
        if (plan.depotFilter != null && plan.depotFilter != code.depot) checkDepotChip(code.depot)
        plan.selected = code
        plan.selectedRow = code.depot to row.name
        val f = plan.markers[code]
        plan.route = (if (f != null) RouteFinder.route(layout, row.pointAlong(f)) else RouteFinder.route(layout, code))?.points
        plan.focus(code)
        showFront(mode != Mode.SEARCH)
        front.row = row
        front.highlighted = found.filter { it.depot == code.depot && it.row.equals(row.name, true) }.map { it.col }.toSet()
        front.selected = code.column
        front.marker = f
        front.endLabels = getString(R.string.pos_left_end) to getString(R.string.pos_right_end)
        frontTitle.text = getString(R.string.loc_front_title, layout.depot(code.depot)?.name ?: code.depot, layout.rowName(row.name), row.levels)
    }

    private fun modelKey(s: SightingWithModel) = s.model?.let { m -> listOfNotNull(m, s.color).joinToString(" · ") } ?: s.barcode

    private fun zoneText(f: Float) = getString(
        when (RowPosition.zone(f)) {
            RowPosition.Zone.LEFT -> R.string.pos_left
            RowPosition.Zone.MIDDLE -> R.string.pos_middle
            RowPosition.Zone.RIGHT -> R.string.pos_right
        },
    )

    private fun showFront(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        front.visibility = v
        frontTitle.visibility = v
    }

    // ---- One column -------------------------------------------------------------------------

    private fun onPlanTap(depot: String, row: String, col: Int) {
        if (editMode) {
            editRow(depot, row)
            return
        }
        showColumn(LocationCode(depot, row.uppercase(), col))
    }

    private fun showColumn(code: LocationCode) {
        if (mode != Mode.COLUMN) before = mode
        mode = Mode.COLUMN
        val layout = store.layout
        val row = layout.rowOf(code) ?: return
        plan.statusMode = before == Mode.OVERVIEW
        if (before == Mode.OVERVIEW) {
            plan.highlighted = emptySet()
            plan.markers = emptyMap()
            plan.shelves = emptyMap()
        }
        selectTarget(code)
        title.text = layout.short(code)
        subtitle.text = layout.describe(code)
        actions.removeAllViews()
        results.removeAllViews()
        openSheet()

        store.query({ dao ->
            val items = dao.column(code.depot, code.row, code.column)
            val sweeps = dao.sweepsOf(code.depot, code.row, code.column).take(HISTORY_COUNT + 1)
            val lines = sweeps.associate { s -> s.id to dao.linesOf(s.id).associate { it.barcode to it.boxes } }
            Triple(items, sweeps, lines)
        }) { (items, sweeps, lines) ->
            if (mode != Mode.COLUMN || plan.selected != code) return@query
            val last = sweeps.firstOrNull()
            subtitle.text = layout.describe(code) + " · " + if (last == null) getString(R.string.loc_column_never) else getString(R.string.col_last_labels, ago(last.finishedAt), items.size)
            actions.removeAllViews()
            if (items.isNotEmpty()) {
                addAction(R.string.find_in_camera, R.drawable.ic_camera, primary = true) {
                    findInCamera(items.map { it.barcode }.toSet(), layout.describe(code))
                }
                addAction(R.string.pick_add, R.drawable.ic_add) {
                    app.pickList.removeAll { it.code == code }
                    app.pickList += PickItem(code, items.mapNotNull { it.model }.distinct().joinToString(", ").ifEmpty { layout.short(code) }, items.map { it.barcode }.toSet())
                    refreshPickChip()
                    Toast.makeText(this, getString(R.string.pick_added, 1), Toast.LENGTH_SHORT).show()
                }
            }
            addAction(R.string.col_label, R.drawable.ic_print) { makeLabels(listOf(code), LabelSheet.DEFAULT_QR_MM) }
            if (last != null) {
                addAction(R.string.col_undo, R.drawable.ic_undo) { confirmUndo(code, last) }
                addAction(R.string.loc_column_empty, R.drawable.ic_done) { confirmEmpty(code) }
            }

            results.removeAllViews()
            if (last == null) {
                results.addView(note(getString(R.string.col_never_help)))
                return@query
            }
            results.addView(section(getString(R.string.col_contents)))
            // Models in the order they stand, left to right, with their sizes.
            val groups = items.groupBy { modelKey(it) }.toList().sortedBy { (_, l) -> l.minOf { it.firstOrder } }
            for ((i, g) in groups.withIndex()) {
                val (key, list) = g
                val known = list.first().model != null
                val name = if (known) key else getString(R.string.loc_model_unknown) + " · …" + key.takeLast(5)
                val sizes = sizesText(list)
                val f = RowPosition.fraction(list.minOf { it.firstOrder }, list.maxOf { maxOf(it.lastOrder, it.firstOrder) }, list.first().total ?: 0)
                results.addView(row("${i + 1}.", name, sizes, zoneText(f), codeColor = 0xFFB0B8C1.toInt()) {
                    query.setText(list.first().model ?: list.first().barcode)
                })
            }
            results.addView(section(getString(R.string.col_history)))
            for ((i, s) in sweeps.take(HISTORY_COUNT).withIndex()) {
                val previous = sweeps.getOrNull(i + 1)
                val diff = SweepDiff.of(previous?.let { lines[it.id] }, lines[s.id].orEmpty())
                results.addView(row(dateShort(s.finishedAt), getString(R.string.labels_read, lines[s.id].orEmpty().size), DiffText.summary(this, diff), if (i == 0) getString(R.string.col_current) else "", codeColor = 0xFFB0B8C1.toInt(), codeMono = false) {})
            }
        }
    }

    private fun confirmUndo(code: LocationCode, last: Sweep) {
        AlertDialog.Builder(this)
            .setTitle(R.string.col_undo)
            .setMessage(getString(R.string.col_undo_body, store.layout.short(code), dateShort(last.finishedAt)))
            .setPositiveButton(R.string.col_undo) { _, _ ->
                store.undoSweep(last.id) {
                    refreshCoverage()
                    showColumn(code)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmEmpty(code: LocationCode) {
        AlertDialog.Builder(this)
            .setTitle(R.string.loc_column_empty)
            .setMessage(getString(R.string.col_empty_body, store.layout.short(code)))
            .setPositiveButton(R.string.loc_column_empty) { _, _ ->
                // An empty sweep: kept in the history like any other, and undoable.
                store.saveSnapshot(com.sweepscan.location.ColumnRecorder.Snapshot(code, emptyList(), System.currentTimeMillis())) {
                    refreshCoverage()
                    showColumn(code)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Pick list --------------------------------------------------------------------------

    private fun addToPick(rows: List<SightingWithModel>) {
        for ((code, items) in rows.groupBy { it.code }) {
            val old = app.pickList.firstOrNull { it.code == code }
            val label = (listOfNotNull(old?.label) + items.map { modelName(it) }).distinct().joinToString(", ")
            app.pickList.removeAll { it.code == code }
            app.pickList += PickItem(code, label, (old?.barcodes.orEmpty()) + items.map { it.barcode })
        }
        refreshPickChip()
    }

    private fun showPick() {
        if (app.pickList.isEmpty()) {
            search()
            return
        }
        mode = Mode.PICK
        val layout = store.layout
        val tour = RouteFinder.tour(layout, app.pickList.map { it.code })
        val order = tour?.order ?: app.pickList.map { it.code }
        checkDepotChip(null)
        plan.statusMode = false
        plan.selected = null
        plan.selectedRow = null
        plan.highlighted = emptySet()
        plan.stops = order
        plan.markers = emptyMap()
        plan.shelves = emptyMap()
        plan.route = tour?.points
        plan.refit()
        showFront(false)
        title.setText(R.string.pick_title)
        subtitle.text = getString(R.string.pick_subtitle, order.size)
        actions.removeAllViews()
        addAction(R.string.find_in_camera, R.drawable.ic_camera, primary = true) {
            findInCamera(app.pickList.flatMap { it.barcodes }.toSet(), getString(R.string.pick_title))
        }
        addAction(R.string.pick_clear, R.drawable.ic_undo) {
            app.pickList.clear()
            refreshPickChip()
            search()
        }
        results.removeAllViews()
        for ((i, code) in order.withIndex()) {
            val item = app.pickList.first { it.code == code }
            val view = row("${i + 1}  ${layout.short(code)}", item.label, layout.describe(code), getString(R.string.pick_done), codeColor = PlanView.TARGET) {
                showColumn(code)
            }
            view.findViewById<TextView>(R.id.row_meta).apply {
                setTextColor(PlanView.ROUTE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(24, 24, 0, 24)
                setOnClickListener {
                    app.pickList.removeAll { it.code == code }
                    refreshPickChip()
                    showPick()
                }
            }
            results.addView(view)
        }
        openSheet()
    }

    // ---- Find in camera ---------------------------------------------------------------------

    private fun findInCamera(barcodes: Set<String>, label: String) {
        if (barcodes.isEmpty()) return
        startActivity(
            MainActivity.intent(this, MainActivity.Task.FIND)
                .putStringArrayListExtra(MainActivity.EXTRA_FIND_BARCODES, ArrayList(barcodes))
                .putExtra(MainActivity.EXTRA_FIND_LABEL, label),
        )
    }

    // ---- Menu: layout, labels, import / export, sample data ---------------------------------

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, if (editMode) R.string.loc_edit_done else R.string.loc_edit)
        menu.menu.add(0, 2, 1, R.string.loc_labels)
        menu.menu.add(0, 3, 2, R.string.loc_import)
        menu.menu.add(0, 4, 3, R.string.loc_export)
        menu.menu.add(0, 6, 4, if (store.demo) R.string.demo_off else R.string.demo_on)
        menu.menu.add(0, 5, 5, R.string.loc_reset_layout)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleEdit()
                2 -> askLabels()
                3 -> launchImport()
                4 -> store.exportCsv(shareDir()) { file -> share(file, "text/csv") }
                5 -> AlertDialog.Builder(this)
                    .setTitle(R.string.loc_reset_layout)
                    .setMessage(R.string.loc_reset_layout_body)
                    .setPositiveButton(R.string.loc_reset_layout) { _, _ -> store.resetLayout(); plan.layout = store.layout; buildDepotChips(); search() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                6 -> toggleDemo()
            }
            true
        }
        menu.show()
    }

    /** Sent / not there for the request this screen was opened for; replies in the group. */
    private fun finishRequest(id: Long, status: com.sweepscan.request.Requests.Status) {
        val r = com.sweepscan.request.Requests.recent(this).firstOrNull { it.id == id } ?: return
        val replied = com.sweepscan.request.Requests.setStatus(this, id, status)
        if (com.sweepscan.request.Requests.replies(this)) {
            if (replied) {
                Toast.makeText(this, R.string.reply_done, Toast.LENGTH_SHORT).show()
            } else {
                com.sweepscan.request.Requests.copyReply(this, com.sweepscan.request.Requests.replyText(this, r, status))
                Toast.makeText(this, R.string.reply_copied, Toast.LENGTH_LONG).show()
                com.sweepscan.request.Requests.openWhatsApp(this)
            }
        }
        finish()
    }

    private fun launchImport() = importLauncher.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream"))

    private fun toggleDemo() {
        val on = !store.demo
        AlertDialog.Builder(this)
            .setTitle(if (on) R.string.demo_on else R.string.demo_off)
            .setMessage(if (on) R.string.demo_on_body else R.string.demo_off_body)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                app.setDemoMode(on) {
                    findViewById<View>(R.id.loc_demo_banner).visibility = if (on) View.VISIBLE else View.GONE
                    plan.layout = store.layout
                    refreshCoverage()
                    refreshPickChip()
                    search()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleEdit() {
        editMode = !editMode
        if (!editMode) Toast.makeText(this, R.string.loc_edit_off, Toast.LENGTH_SHORT).show()
        query.setText("")
        showOverview()
    }

    /** Columns, shelves, which way the rack faces and the numbering direction of one row. */
    private fun editRow(depotCode: String, rowName: String) {
        val layout = store.layout
        val depot = layout.depot(depotCode) ?: return
        val row = depot.row(rowName) ?: return
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun number(label: Int, value: Int): EditText {
            box.addView(TextView(this).apply { setText(label) })
            return EditText(this).apply { setText(value.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }.also { box.addView(it) }
        }
        val columns = number(R.string.loc_edit_columns, row.columns)
        val levels = number(R.string.loc_edit_levels, row.levels)
        box.addView(TextView(this).apply { setText(R.string.loc_edit_face) })
        val faces = Face.entries
        val face = Spinner(this).apply {
            adapter = ArrayAdapter(this@LocationActivity, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.loc_faces))
            setSelection(faces.indexOf(row.face))
        }
        box.addView(face)
        val reverse = CheckBox(this).apply { setText(R.string.loc_edit_reverse); isChecked = row.reverse }
        box.addView(reverse)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.loc_edit_title, depot.name, row.name))
            .setView(box)
            .setPositiveButton(R.string.loc_edit_save) { _, _ ->
                val c = columns.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: row.columns
                val l = levels.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: row.levels
                val updated = row.copy(columns = c, levels = l, face = faces[face.selectedItemPosition], reverse = reverse.isChecked)
                store.saveLayout(layout.withRow(depotCode, row.name, updated))
                plan.layout = store.layout
                plan.selectedRow = depotCode to row.name
                refreshCoverage()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askLabels() {
        val depotsList = store.layout.depots
        val names = depotsList.map { "${it.name} (${it.columnCount})" }.toTypedArray()
        val checked = BooleanArray(depotsList.size) { true }
        AlertDialog.Builder(this)
            .setTitle(R.string.loc_labels)
            .setMultiChoiceItems(names, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton(R.string.loc_labels_75) { _, _ -> makeLabels(depotsList.filterIndexed { i, _ -> checked[i] }.flatMap { it.codes() }, 75f) }
            .setNeutralButton(R.string.loc_labels_50) { _, _ -> makeLabels(depotsList.filterIndexed { i, _ -> checked[i] }.flatMap { it.codes() }, 50f) }
            .show()
    }

    private fun makeLabels(codes: List<LocationCode>, mm: Float) {
        if (codes.isEmpty()) return
        val name = if (codes.size == 1) "etiket-${codes[0].text}.pdf" else "konum-etiketleri-${mm.toInt()}mm.pdf"
        val file = File(shareDir(), name)
        Thread {
            val pages = runCatching { LabelSheet.write(store.layout, codes, file, mm) }.getOrElse { -1 }
            runOnUiThread {
                if (pages < 0) {
                    Toast.makeText(this, R.string.loc_labels_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.loc_labels_ready, codes.size, pages))
                    .setMessage(R.string.loc_labels_scale)
                    .setPositiveButton(R.string.loc_labels_print) { _, _ -> LabelSheet.print(this, file, "SweepScan etiketler") }
                    .setNeutralButton(R.string.loc_share) { _, _ -> share(file, "application/pdf") }
                    .show()
            }
        }.start()
    }

    private fun shareDir() = File(cacheDir, "share").apply { mkdirs() }

    private fun share(file: File, type: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, file.name))
    }

    // ---- Small helpers ----------------------------------------------------------------------

    private fun modelName(s: SightingWithModel) =
        listOfNotNull(s.model, s.color).joinToString(" · ").ifEmpty { getString(R.string.loc_model_unknown) }

    private fun isOld(ms: Long) = System.currentTimeMillis() - ms >= FRESH_MS

    private fun dateShort(ms: Long) = SimpleDateFormat("d MMM HH:mm", tr).format(Date(ms))

    /** "bugün 14:05", "dün", "3 gün önce", then the date. */
    private fun ago(ms: Long): String {
        val days = ((startOfToday() - ms) / DAY_MS).let { if (ms >= startOfToday()) -1 else it }
        return when {
            days < 0 -> getString(R.string.ago_today, SimpleDateFormat("HH:mm", tr).format(Date(ms)))
            days == 0L -> getString(R.string.ago_yesterday)
            days < 14 -> getString(R.string.ago_days, days + 1)
            else -> SimpleDateFormat("d MMM", tr).format(Date(ms))
        }
    }

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun addAction(text: Int, icon: Int, primary: Boolean = false, onClick: () -> Unit) {
        val b = LayoutInflater.from(this).inflate(if (primary) R.layout.item_action_primary else R.layout.item_action, actions, false) as MaterialButton
        b.setText(text)
        b.setIconResource(icon)
        b.setOnClickListener { onClick() }
        actions.addView(b)
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFFE3E8ED.toInt())
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(4, (18 * resources.displayMetrics.density).toInt(), 4, 2)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFFB0B8C1.toInt())
        textSize = 13f
        setPadding(4, (16 * resources.displayMetrics.density).toInt(), 4, 0)
    }

    private fun row(
        code: String, title: String, sub: String?, meta: String, codeColor: Int = 0xFFFFFFFF.toInt(),
        stale: Boolean = false, codeMono: Boolean = true, onClick: () -> Unit,
    ): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_row, results, false)
        v.findViewById<TextView>(R.id.row_code).apply {
            text = code
            setTextColor(codeColor)
            if (!codeMono) {
                typeface = Typeface.DEFAULT
                textSize = 14f
            }
        }
        v.findViewById<TextView>(R.id.row_title).text = title
        v.findViewById<TextView>(R.id.row_sub).apply {
            text = sub
            visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.row_meta).apply {
            text = meta + if (stale) " ⚠" else ""
            if (stale) setTextColor(0xFFFFB74D.toInt())
        }
        v.setOnClickListener { onClick() }
        return v
    }

    companion object {
        const val EXTRA_QUERY = "query"
        /** The query is a barcode just read by the camera. */
        const val EXTRA_SCANNED = "scanned"
        /** Opened for this request (its id): the answer card offers Gönderdim / Yok. */
        const val EXTRA_REQUEST = "request"
        /** Open with the keyboard up. */
        const val EXTRA_FOCUS = "focus"
        /** One of the tools, asked by the settings screen. */
        const val EXTRA_ACTION = "action"
        const val ACTION_EDIT = "edit"
        const val ACTION_LABELS = "labels"
        const val ACTION_IMPORT = "import"
        const val ACTION_EXPORT = "export"
        const val ACTION_PICK = "pick"
        /** A sweep older than this is shown as possibly out of date. */
        const val FRESH_MS = 7L * 24 * 3600 * 1000
        private const val DAY_MS = 24L * 3600 * 1000
        private const val NEXT_COUNT = 8
        private const val HISTORY_COUNT = 6
    }
}
