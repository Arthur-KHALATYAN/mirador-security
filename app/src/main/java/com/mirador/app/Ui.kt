package com.mirador.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Palette de l'interface ; s'adapte automatiquement au mode sombre. */
object Ui {
    @Volatile var dark = false
    const val PRIMARY = 0xFF2F5BEA.toInt()
    const val PRIMARY_DARK = 0xFF1E3FB8.toInt()
    val TEXT: Int get() = if (dark) 0xFFE8EAED.toInt() else 0xFF111827.toInt()
    val MUTED: Int get() = if (dark) 0xFF9AA3AF.toInt() else 0xFF6B7280.toInt()
    val BG: Int get() = if (dark) 0xFF0E1116.toInt() else 0xFFF4F6FB.toInt()
    val SURFACE: Int get() = if (dark) 0xFF171B22.toInt() else Color.WHITE
    val CARD: Int get() = SURFACE
    val TONAL: Int get() = if (dark) 0xFF242A35.toInt() else 0xFFEDF0F7.toInt()
    val OUTLINE: Int get() = if (dark) 0xFF262C36.toInt() else 0xFFE6E9F0.toInt()
    /** Bouton secondaire (fond doux). Gardé sous ce nom pour les écrans existants. */
    val DARK: Int get() = TONAL
    val ACCENT_TEXT: Int get() = if (dark) 0xFF8FA8FF.toInt() else PRIMARY

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.toast(msg: String) = Toast.makeText(this, I18n.tr(msg), Toast.LENGTH_LONG).show()

fun Context.safeStart(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

/** Effet d'appui (ondulation) sur une vue cliquable. */
fun View.clickable(radius: Float, onClick: () -> Unit) {
    val mask = rounded(Color.WHITE, radius)
    val ripple = if (Ui.dark) 0x33FFFFFF else 0x1F000000
    foreground = RippleDrawable(ColorStateList.valueOf(ripple), null, mask)
    isClickable = true
    setOnClickListener { onClick() }
}

/** Remplace le contenu de l'écran par une colonne scrollable et la renvoie. */
fun Activity.screen(): LinearLayout {
    Ui.dark = isNight()
    applySystemBars()

    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(10), dp(18), dp(48))
        clipToPadding = false
    }
    // Barre de retour sur tous les écrans sauf l'accueil
    if (this !is MainActivity) {
        val back = TextView(this).apply {
            text = "Retour"
            textSize = 15f
            typeface = Ui.medium
            setTextColor(Ui.ACCENT_TEXT)
            setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_back, Ui.ACCENT_TEXT, 20), null, null, null)
            compoundDrawablePadding = dp(6)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(14), dp(10))
            clickable(dp(20).toFloat()) { onBackPressedCompat() }
        }
        col.addView(back, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
    }
    val scroll = ScrollView(this).apply {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        addView(col)
    }
    // Affichage bord à bord (Android 11+) : on décale le contenu sous la barre d'état,
    // au-dessus de la barre de navigation et du clavier.
    if (Build.VERSION.SDK_INT >= 30) {
        val base = intArrayOf(dp(18), dp(10), dp(18), dp(48))
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            // Haut : la zone défilante commence sous la barre d'état (le contenu ne passe pas sous l'heure)
            scroll.setPadding(0, bars.top, 0, 0)
            col.setPadding(base[0] + bars.left, base[1], base[2] + bars.right, base[3] + maxOf(bars.bottom, ime.bottom))
            insets
        }
    }
    // Traduction anglaise automatique de tout ce qui s'affiche (y compris ajouté plus tard)
    if (I18n.english) scroll.viewTreeObserver.addOnGlobalLayoutListener { I18n.translateTree(scroll) }
    setContentView(scroll)
    return col
}

/** Barres système transparentes, icônes claires ou foncées selon le thème. */
private fun Activity.applySystemBars() {
    if (Build.VERSION.SDK_INT >= 30) {
        // Android 15+ : bord à bord par défaut ; avant, on l'active nous-mêmes
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        }
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (Ui.dark) 0 else light, light)
        // Sous Android 15+ les barres sont toujours transparentes ; avant, on le demande
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        }
    } else {
        @Suppress("DEPRECATION")
        window.statusBarColor = Ui.BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = Ui.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (Ui.dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
}

/** Affiche une boîte de dialogue en la traduisant si besoin. */
fun android.app.AlertDialog.Builder.showTr(): android.app.AlertDialog =
    show().also { d -> d.window?.decorView?.let { I18n.translateTree(it) } }

/** Texte affiché tel quel, jamais traduit (contenu d'un SMS, adresse…). */
fun TextView.raw(): TextView = apply { tag = I18n.NO_TR }

private fun Activity.onBackPressedCompat() = finish()

fun LinearLayout.label(
    text: CharSequence,
    size: Float = 15f,
    bold: Boolean = false,
    color: Int = Ui.TEXT,
    top: Int = 4,
): TextView {
    // Les intertitres de section (gras, 16–19, espacés) deviennent des en-têtes discrets
    val isSection = bold && size in 16f..19.5f && top >= 14 && color == Ui.TEXT
    val tv = TextView(context).apply {
        when {
            isSection -> {
                this.text = I18n.tr(text).uppercase()
                textSize = 12.5f
                letterSpacing = 0.08f
                typeface = Ui.medium
                setTextColor(Ui.MUTED)
            }
            else -> {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) typeface = if (size >= 20f) Typeface.create("sans-serif", Typeface.BOLD) else Ui.medium
                if (size >= 22f) letterSpacing = -0.01f
                setLineSpacing(0f, 1.12f)
            }
        }
    }
    val topMargin = if (isSection) context.dp(top + 6) else context.dp(top)
    addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        this.topMargin = topMargin
        if (isSection) bottomMargin = context.dp(2)
    })
    return tv
}

/**
 * Bouton : plein (couleur principale ou couleur d'un verdict) ou doux (`Ui.DARK`).
 */
fun LinearLayout.btn(text: String, color: Int = Ui.PRIMARY, onClick: () -> Unit): Button {
    val tonal = color == Ui.TONAL
    val radius = context.dp(14).toFloat()
    val b = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        typeface = Ui.medium
        stateListAnimator = null
        minHeight = context.dp(50)
        setTextColor(if (tonal) Ui.TEXT else Color.WHITE)
        val ripple = if (tonal) (if (Ui.dark) 0x33FFFFFF else 0x22000000) else 0x40FFFFFF
        background = RippleDrawable(ColorStateList.valueOf(ripple), rounded(color, radius), rounded(Color.WHITE, radius))
        setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
        setOnClickListener { onClick() }
    }
    addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(10) })
    return b
}

/** Carte : blanche (par défaut) ou teintée (couleur d'un verdict). */
fun LinearLayout.card(bg: Int = Ui.CARD): LinearLayout {
    val plain = bg == Ui.SURFACE
    val c = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(16))
        background = rounded(bg, context.dp(20).toFloat(), context.dp(1), if (plain) Ui.OUTLINE else Color.TRANSPARENT)
        if (plain && !Ui.dark) elevation = context.dp(1).toFloat()
    }
    addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(12) })
    return c
}

/** Sous-conteneur vide (pour remplir plus tard depuis un thread). */
fun LinearLayout.box(): LinearLayout {
    val b = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    return b
}

/** Pastille d'état (ex. « Actif »). */
fun pill(c: Context, text: String, color: Int): TextView = TextView(c).apply {
    this.text = text
    textSize = 12f
    typeface = Ui.medium
    setTextColor(color)
    background = rounded((color and 0x00FFFFFF) or 0x22000000, c.dp(20).toFloat())
    setPadding(c.dp(10), c.dp(4), c.dp(10), c.dp(4))
}

/** Icône vectorielle teintée, à la taille voulue (dp). */
fun Context.tinted(res: Int, color: Int, sizeDp: Int = 24): android.graphics.drawable.Drawable =
    getDrawable(res)!!.mutate().apply {
        setTint(color)
        setBounds(0, 0, dp(sizeDp), dp(sizeDp))
    }

/** Pastille carrée arrondie avec une icône (fond = couleur de l'icône, très atténuée). */
fun iconBadge(c: Context, res: Int, tint: Int, sizeDp: Int = 40): android.widget.ImageView =
    android.widget.ImageView(c).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        val alpha = if (Ui.dark) 0x33000000 else 0x1A000000
        background = rounded((tint and 0x00FFFFFF) or alpha, c.dp(12).toFloat())
        val pad = c.dp(sizeDp) * 22 / 100
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(c.dp(sizeDp), c.dp(sizeDp))
    }

private fun LinearLayout.rowWith(lead: View, leadWidth: Int, title: String, subtitle: String?, titleColor: Int, onClick: (() -> Unit)?): LinearLayout {
    val r = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(6), context.dp(12), context.dp(6), context.dp(12))
    }
    r.addView(lead, LinearLayout.LayoutParams(leadWidth, if (lead is android.widget.ImageView) leadWidth else WRAP_CONTENT))
    val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(context).apply {
        text = title
        textSize = 15f
        typeface = Ui.medium
        setTextColor(titleColor)
    })
    subtitle?.let {
        texts.addView(TextView(context).apply {
            text = it
            textSize = 13f
            setTextColor(Ui.MUTED)
            setLineSpacing(0f, 1.1f)
            setPadding(0, context.dp(1), 0, 0)
        })
    }
    r.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dp(14) })
    if (onClick != null) {
        r.addView(android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(Ui.MUTED)
            alpha = 0.7f
        }, LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply { marginStart = context.dp(8) })
        r.clickable(context.dp(14).toFloat(), onClick)
    }
    addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    return r
}

/** Ligne de liste avec une icône vectorielle dans une pastille. */
fun LinearLayout.row(iconRes: Int, tint: Int, title: String, subtitle: String? = null, titleColor: Int = Ui.TEXT, onClick: (() -> Unit)? = null): LinearLayout =
    rowWith(iconBadge(context, iconRes, tint), context.dp(40), title, subtitle, titleColor, onClick)

/** Ligne de liste avec un emoji (écrans secondaires). */
fun LinearLayout.row(icon: String, title: String, subtitle: String? = null, titleColor: Int = Ui.TEXT, onClick: (() -> Unit)? = null): LinearLayout =
    rowWith(TextView(context).apply { text = icon; textSize = 20f; gravity = Gravity.CENTER }, context.dp(40), title, subtitle, titleColor, onClick)

/** En-tête d'écran : pastille d'icône, grand titre, description. */
fun LinearLayout.header(iconRes: Int, tint: Int, title: String, subtitle: String? = null) {
    val h = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(8), 0, 0)
    }
    h.addView(iconBadge(context, iconRes, tint, 48))
    h.addView(TextView(context).apply {
        text = title
        textSize = 24f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = -0.01f
        setTextColor(Ui.TEXT)
        setPadding(context.dp(14), 0, 0, 0)
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    addView(h)
    subtitle?.let { label(it, 14f, color = Ui.MUTED, top = 10) }
}

/** Titre précédé d'une petite icône teintée (cartes, résultats). */
fun LinearLayout.iconTitle(iconRes: Int, color: Int, text: CharSequence, size: Float = 16f, top: Int = 0): TextView {
    val tv = TextView(context).apply {
        this.text = text
        textSize = size
        typeface = if (size >= 20f) Typeface.create("sans-serif", Typeface.BOLD) else Ui.medium
        setTextColor(color)
        val px = (size * 1.35f).toInt()
        setCompoundDrawablesRelativeWithIntrinsicBounds(context.tinted(iconRes, color, px), null, null, null)
        compoundDrawablePadding = context.dp(8)
        gravity = Gravity.CENTER_VERTICAL
    }
    addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(top) })
    return tv
}

/** Icône associée à un verdict. */
val Verdict.iconRes: Int
    get() = when (this) {
        Verdict.SAFE -> R.drawable.ic_check_circle
        Verdict.CAUTION -> R.drawable.ic_warning
        Verdict.DANGER -> R.drawable.ic_block
    }

/** Séparateur fin entre deux lignes. */
fun LinearLayout.divider() {
    addView(View(context).apply { setBackgroundColor(Ui.OUTLINE) },
        LinearLayout.LayoutParams(MATCH_PARENT, context.dp(1)).apply { marginStart = context.dp(60) })
}

data class Tile(val iconRes: Int, val tint: Int, val title: String, val status: String, val statusColor: Int, val onClick: () -> Unit)

/** Grille de tuiles (2 colonnes). */
fun LinearLayout.tiles(items: List<Tile>, columns: Int = 2) {
    items.chunked(columns).forEach { rowItems ->
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        rowItems.forEachIndexed { i, t ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
                background = rounded(Ui.SURFACE, context.dp(20).toFloat(), context.dp(1), Ui.OUTLINE)
                if (!Ui.dark) elevation = context.dp(1).toFloat()
                clickable(context.dp(20).toFloat(), t.onClick)
            }
            tile.addView(iconBadge(context, t.iconRes, t.tint, 42))
            tile.addView(TextView(context).apply {
                text = t.title
                textSize = 15f
                typeface = Ui.medium
                setTextColor(Ui.TEXT)
                setPadding(0, context.dp(12), 0, 0)
            })
            // Statut : petit point de couleur + texte
            val status = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(4), 0, 0)
            }
            status.addView(View(context).apply { background = rounded(t.statusColor, context.dp(4).toFloat()) },
                LinearLayout.LayoutParams(context.dp(7), context.dp(7)))
            status.addView(TextView(context).apply {
                text = t.status
                textSize = 12.5f
                setTextColor(if (t.statusColor == Ui.MUTED) Ui.MUTED else t.statusColor)
                setPadding(context.dp(6), 0, 0, 0)
            })
            tile.addView(status)
            row.addView(tile, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = context.dp(12)
            })
        }
        // Complète la dernière ligne pour garder la grille alignée
        repeat(columns - rowItems.size) {
            row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = context.dp(12) })
        }
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(12) })
    }
}

/** Anneau de score (0–100), arc de la couleur de l'état. */
class ScoreRing(c: Context, private val score: Int, arcColor: Int) : View(c) {
    private val stroke = c.dp(9).toFloat()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = 0x26FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = arcColor; strokeCap = Paint.Cap.ROUND
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = c.dp(30).toFloat()
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = c.dp(11).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val r = RectF(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        canvas.drawArc(r, 0f, 360f, false, track)
        canvas.drawArc(r, -90f, 360f * score / 100f, false, arc)
        val cy = height / 2f
        canvas.drawText("$score", width / 2f, cy + big.textSize / 3, big)
        canvas.drawText("/ 100", width / 2f, cy + big.textSize / 3 + small.textSize * 1.4f, small)
    }
}

/** Carte « héros » en dégradé (haut de l'accueil). */
fun LinearLayout.heroCard(from: Int, to: Int): LinearLayout {
    val c = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply {
            cornerRadius = context.dp(24).toFloat()
        }
        elevation = context.dp(3).toFloat()
    }
    addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(14) })
    return c
}
