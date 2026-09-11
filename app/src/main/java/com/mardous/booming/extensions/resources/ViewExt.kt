/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.extensions.resources

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.MenuRes
import androidx.appcompat.widget.Toolbar
import androidx.compose.ui.graphics.toArgb
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnLayout
import androidx.core.view.drawToBitmap
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.Shapeable
import com.google.android.material.slider.Slider
import com.mardous.booming.R
import com.mardous.booming.core.palette.CoverColorState
import com.mardous.booming.extensions.dip
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.extensions.resolveColor
import com.mardous.booming.ui.component.views.MorphicIconButton
import com.mardous.booming.util.Preferences
import com.skydoves.balloon.ArrowPositionRules
import com.skydoves.balloon.Balloon
import com.skydoves.balloon.BalloonAnimation
import com.skydoves.balloon.BalloonSizeSpec
import com.skydoves.balloon.createBalloon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.html.HtmlPlugin
import kotlin.math.abs
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupTextProvider
import me.zhanghai.android.fastscroll.Predicate
import com.google.android.material.R as M3R

const val BOOMING_ANIM_TIME = 350L

val View.backgroundColor: Int
    get() = (background as? ColorDrawable)?.color
        ?: (background as? MaterialShapeDrawable)?.fillColor?.defaultColor
        ?: Color.TRANSPARENT

fun View.showBounceAnimation() {
    clearAnimation()
    scaleX = 0.9f
    scaleY = 0.9f
    isVisible = true
    pivotX = (width / 2).toFloat()
    pivotY = (height / 2).toFloat()

    animate().setDuration(200)
        .setInterpolator(DecelerateInterpolator())
        .scaleX(1.1f)
        .scaleY(1.1f)
        .withEndAction {
            animate().setDuration(200)
                .setInterpolator(AccelerateInterpolator())
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .start()
        }
        .start()
}

typealias AnimationCompleted = () -> Unit

fun View.show(animate: Boolean = false, onCompleted: AnimationCompleted? = null) {
    if (!animate) {
        alpha = 1f
        isVisible = true
        onCompleted?.invoke()
    } else {
        if (isVisible && alpha == 1f) {
            onCompleted?.invoke()
            return
        }
        this.animate()
            .alpha(1f)
            .setDuration(BOOMING_ANIM_TIME)
            .withStartAction {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                isVisible = true
            }
            .withEndAction {
                setLayerType(View.LAYER_TYPE_NONE, null)
                onCompleted?.invoke()
            }
            .start()
    }
}

fun View.hide(animate: Boolean = false, onCompleted: AnimationCompleted? = null) {
    if (!animate) {
        isVisible = false
        onCompleted?.invoke()
    } else {
        if (!isVisible) {
            onCompleted?.invoke()
            return
        }
        this.animate()
            .alpha(0f)
            .setDuration(BOOMING_ANIM_TIME)
            .withStartAction {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            .withEndAction {
                setLayerType(View.LAYER_TYPE_NONE, null)
                isVisible = false
                onCompleted?.invoke()
            }
            .start()
    }
}

fun View.reactionToKey(targetKeyCode: Int, action: (KeyEvent) -> Unit) {
    setOnKeyListener { view, keyCode, keyEvent ->
        if (keyEvent.hasNoModifiers()) {
            if (keyEvent.action == KeyEvent.ACTION_UP) {
                if (keyCode == targetKeyCode) {
                    view.cancelLongPress()
                    action(keyEvent)
                    return@setOnKeyListener true
                }
            }
        }
        false
    }
}

fun View.focusAndShowKeyboard() {
    /**
     * This is to be called when the window already has focus.
     */
    fun View.showTheKeyboardNow() {
        if (isFocused) {
            post {
                // We still post the call, just in case we are being notified of the windows focus
                // but InputMethodManager didn't get properly setup yet.
                val imm = context.getSystemService<InputMethodManager>()
                imm?.showSoftInput(this, 0)
            }
        }
    }

    requestFocus()
    if (hasWindowFocus()) {
        // No need to wait for the window to get focus.
        showTheKeyboardNow()
    } else {
        // We need to wait until the window gets focus.
        viewTreeObserver.addOnWindowFocusChangeListener(
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    // This notification will arrive just before the InputMethodManager gets set up.
                    if (hasFocus) {
                        this@focusAndShowKeyboard.showTheKeyboardNow()
                        // It’s very important to remove this listener once we are done.
                        viewTreeObserver.removeOnWindowFocusChangeListener(this)
                    }
                }
            })
    }
}

fun View.addPaddingRelative(
    start: Int = 0,
    top: Int = 0,
    end: Int = 0,
    bottom: Int = 0
) {
    updatePaddingRelative(paddingStart + start, paddingTop + top, paddingEnd + end, paddingBottom + bottom)
}

fun View.centerPivot() {
    post {
        pivotX = (width / 2).toFloat()
        pivotY = (height / 2).toFloat()
    }
}

fun View.hitTest(x: Int, y: Int): Boolean {
    val tx = (translationX + 0.5f)
    val ty = (translationY + 0.5f)
    val left = left + tx
    val right = right + tx
    val top = top + ty
    val bottom = bottom + ty

    return x >= left && x <= right && y >= top && y <= bottom
}

fun View.animateBackgroundColor(
    toColor: Int,
    duration: Long = 300,
    onCompleted: AnimationCompleted? = null
): Animator {
    val fromColor = backgroundColor
    return ObjectAnimator.ofArgb(this, "backgroundColor", fromColor, toColor).apply {
        this.doOnEnd { onCompleted?.invoke() }
        this.duration = duration
    }
}

fun View.animateTintColor(
    fromColor: Int,
    toColor: Int,
    duration: Long = 300,
    isForeground: Boolean = false,
    isIconButton: Boolean = false
): Animator {
    return ValueAnimator.ofArgb(fromColor, toColor).apply {
        this.duration = duration
        addUpdateListener { animation ->
            val animatedColor = animation.animatedValue as Int
            val colorStateList = animatedColor.toColorStateList()

            if (isForeground) {
                foregroundTintList = colorStateList
            } else when (this@animateTintColor) {
                is Toolbar -> colorizeToolbar(animatedColor)
                is Slider -> applyColor(animatedColor)
                is SeekBar -> applyColor(animatedColor)
                is FloatingActionButton -> applyColor(animatedColor)
                is MaterialButton -> applyColor(animatedColor, isIconButton)
                is ImageButton -> {
                    val imageTintList = getPrimaryTextColor(context, animatedColor.isColorLight)
                    ImageViewCompat.setImageTintList(this@animateTintColor, imageTintList.toColorStateList())
                    backgroundTintList = colorStateList
                }
                is ImageView -> ImageViewCompat.setImageTintList(this@animateTintColor, colorStateList)
                is TextView -> applyColor(animatedColor)
                is MorphicIconButton -> applyColor(animatedColor)
                else -> backgroundTintList = colorStateList
            }
        }
    }
}

fun ImageView.removeHorizontalMarginIfRequired() {
    if (Preferences.largerHeaderImage) {
        doOnLayout {
            updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = 0; marginEnd = 0; }
        }
    }
}

fun TextView.setMarquee(marquee: Boolean) {
    isFocusable = marquee
    isFocusableInTouchMode = marquee
    setHorizontallyScrolling(marquee)
    if (marquee) {
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isHorizontalFadingEdgeEnabled = true
    } else {
        ellipsize = TextUtils.TruncateAt.END
    }
    isSelected = marquee
}

fun TextView.setMarkdownText(str: String) {
    val markwon = Markwon.builder(context)
        .usePlugin(HtmlPlugin.create()) // basic Html tags
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                val typedColor = TypedValue()
                context.theme.resolveAttribute(com.google.android.material.R.attr.dividerColor, typedColor, true)

                builder.headingBreakColor("#00ffffff".toColorInt())
                    .thematicBreakColor(typedColor.data)
                    .thematicBreakHeight(2)
                    .bulletWidth(12)
                    .headingTextSizeMultipliers(
                        floatArrayOf(2f, 1.5f, 1f, .83f, .67f, .55f)
                    )
            }
        })
        .build()

    markwon.setMarkdown(this, str)
}

fun ImageView.useAsIcon() {
    val iconPadding = context.dip(R.dimen.list_item_image_icon_padding)
    setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
    clearColorFilter()
}

fun <T> T.setCornerRadius(cornerRadiusDp: Float) where T : View, T : Shapeable {
    val radiusPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        cornerRadiusDp,
        this.resources.displayMetrics
    )
    this.shapeAppearanceModel = this.shapeAppearanceModel
        .toBuilder()
        .setAllCornerSizes(radiusPx)
        .build()
}

fun Toolbar.inflateMenu(
    @MenuRes menuId: Int,
    itemClickListener: Toolbar.OnMenuItemClickListener,
    menuConsumer: ((Menu) -> Unit)? = null
) {
    inflateMenu(menuId)
    setOnMenuItemClickListener(itemClickListener)
    menuConsumer?.invoke(menu)
}

fun RecyclerView.useLinearLayout() {
    layoutManager = LinearLayoutManager(context)
}

fun RecyclerView.safeUpdateWithRetry(
    maxRetries: Int = 5,
    delayMillis: Long = 16L, // ~1 frame on 60fps
    block: RecyclerView.() -> Unit
) {
    fun tryUpdate(attempt: Int) {
        if (!isAnimating && !isComputingLayout) {
            block()
        } else if (attempt < maxRetries) {
            postDelayed({ tryUpdate(attempt + 1) }, delayMillis)
        }
    }
    tryUpdate(0)
}

fun RecyclerView.destroyOnDetach() {
    layoutManager?.let {
        if (it is LinearLayoutManager) {
            it.recycleChildrenOnDetach = true
        }
    }
}

fun RecyclerView.onVerticalScroll(
    lifecycleOwner: LifecycleOwner,
    onScrollUp: () -> Unit = {},
    onScrollDown: () -> Unit = {}
) {
    val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy > 0) {
                onScrollDown()
            } else if (dy < 0) {
                onScrollUp()
            }
        }
    }
    addOnScrollListener(scrollListener)
    lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            removeOnScrollListener(scrollListener)
        }
    })
}

fun ViewGroup.createFastScroller(
    disablePopup: Boolean = false,
    longPressActivation: Boolean = true
): FastScroller {
    val thumbDrawable = ContextCompat.getDrawable(context, R.drawable.scroller_thumb)
    val trackDrawable = ContextCompat.getDrawable(context, R.drawable.scroller_track)
    val fastScrollerBuilder = FastScrollerBuilder(this)
    fastScrollerBuilder.useMd2Style()
    if (thumbDrawable != null) {
        fastScrollerBuilder.setThumbDrawable(thumbDrawable)
    }
    if (trackDrawable != null) {
        fastScrollerBuilder.setTrackDrawable(trackDrawable)
    }
    if (disablePopup) {
        fastScrollerBuilder.setPopupTextProvider { _, _ -> "" }
    }
    val helper = if (longPressActivation && this is RecyclerView) {
        // The handle's touch target covers the item buttons on the right edge
        // of each row, eating their taps. Let taps pass through to the list
        // content and only activate the scroller on a long press.
        LongPressFastScrollViewHelper(this).also {
            fastScrollerBuilder.setViewHelper(it)
        }
    } else null
    val fastScroller = fastScrollerBuilder.build()
    helper?.attach(fastScroller)
    return fastScroller
}

/**
 * [FastScroller.ViewHelper] that gates the handle behind a long press: taps
 * on the handle's touch target pass through to the list content (keeping the
 * item buttons under it clickable), while a long press activates the thumb
 * drag. The touch target area itself is unchanged.
 */
private class LongPressFastScrollViewHelper(
    private val recyclerView: RecyclerView
) : FastScroller.ViewHelper {

    private val touchListener = LongPressFastScrollTouchListener(recyclerView)

    override fun addOnPreDrawListener(runnable: Runnable) {
        // The library wires this hook through an ItemDecoration, not a
        // ViewTreeObserver listener: fragments create the scroller before the
        // RecyclerView is attached to the window, and a pre-draw listener
        // registered on the tree observer of an unattached view is dropped
        // when the view attaches — the scrollbar would then never show.
        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                runnable.run()
                touchListener.syncThumbOverlay()
            }
        })
    }

    override fun addOnScrollChangedListener(runnable: Runnable) {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                runnable.run()
            }
        })
    }

    override fun addOnTouchEventListener(predicate: Predicate<MotionEvent>) {
        touchListener.fastScrollPredicate = predicate
        recyclerView.addOnItemTouchListener(touchListener)
    }

    // Total content height. The library computes scrollbar enablement as
    // getScrollRange() - view.getHeight(), so returning the scrollable delta
    // here would double-subtract the viewport and keep the scrollbar disabled.
    // The padding is counted here: the list's physical scroll range includes
    // it, so excluding it would make the thumb overflow past the bottom on
    // padded lists (while the mini player is shown) and cap the drag before
    // the last item reaches the player's upper edge.
    override fun getScrollRange(): Int =
        recyclerView.computeVerticalScrollRange() +
            recyclerView.paddingTop + recyclerView.paddingBottom

    override fun getScrollOffset(): Int = recyclerView.computeVerticalScrollOffset()

    override fun scrollTo(offset: Int) {
        recyclerView.stopScroll()
        if (touchListener.scrollerActive) {
            val height = recyclerView.height
            val padTop = recyclerView.paddingTop
            val padBottom = recyclerView.paddingBottom
            val thumb = touchListener.thumbView
            val scrollRange = getScrollRange() - height
            if (thumb != null && thumb.height > 0 && scrollRange > 0) {
                val track = height - padTop - padBottom - thumb.height
                if (track > 0) {
                    // Absolute mapping: the thumb's centre tracks the finger,
                    // and the scroll is proportional to the thumb's position
                    // in its track. Unlike an incremental mapping, this never
                    // locks the drag range to the activation point — pressing
                    // anywhere can drag to the full bottom, and pressing at
                    // the bottom can pull back up.
                    val ratio = ((touchListener.lastFingerY - padTop - thumb.height / 2f) / track)
                        .coerceIn(0f, 1f)
                    val target = (ratio * scrollRange).toInt()
                    recyclerView.scrollBy(0, target - recyclerView.computeVerticalScrollOffset())
                    return
                }
            }
        }
        recyclerView.scrollBy(0, offset - recyclerView.computeVerticalScrollOffset())
    }

    override fun getPopupText(): CharSequence? {
        val provider = recyclerView.adapter as? PopupTextProvider ?: return null
        val position =
            (recyclerView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
        return provider.getPopupText(recyclerView, position)
    }

    /**
     * Called once the [FastScroller] has been built: wires the scroller's
     * thumb view into the touch listener, which needs to make it visible
     * (alpha > 0) before feeding it a synthetic DOWN, since the library only
     * starts a drag on a visible thumb.
     */
    fun attach(fastScroller: FastScroller) {
        touchListener.thumbView = try {
            val field = FastScroller::class.java.getDeclaredField("mThumbView")
            field.isAccessible = true
            field.get(fastScroller) as? View
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Guards the fast scroller's touch input. A touch inside the handle's touch
 * target (the right edge strip) is passed through to the list untouched: taps
 * hit the content and swipes scroll natively, exactly as if the scroller
 * wasn't there. Only if the finger stays put for the long-press timeout is
 * the gesture taken over — the list's current touch is cancelled, a synthetic
 * DOWN is fed to the fast scroller to start its drag, and from then on this
 * listener intercepts the gesture and forwards every event to it.
 */
private class LongPressFastScrollTouchListener(
    private val recyclerView: RecyclerView
) : RecyclerView.SimpleOnItemTouchListener() {

    /** Wired by [LongPressFastScrollViewHelper.addOnTouchEventListener]. */
    var fastScrollPredicate: Predicate<MotionEvent>? = null

    /** The scroller's thumb view, wired by [LongPressFastScrollViewHelper.attach]. */
    var thumbView: View? = null

    init {
        // Put the thumb back into the list's own overlay when the list
        // detaches, so it does not linger on the root overlay after the
        // fragment (and its view hierarchy) is gone.
        recyclerView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: View) {
                if (thumbInHostOverlay) {
                    val thumb = thumbView
                    if (thumb != null) {
                        // Pull it out of the root overlay first: a view with
                        // a parent cannot be added to another overlay.
                        v.rootView?.findViewById<ViewGroup>(R.id.mainContent)
                            ?.overlay?.remove(thumb)
                        recyclerView.overlay.add(thumb)
                    }
                    thumbInHostOverlay = false
                }
            }

            override fun onViewAttachedToWindow(v: View) {}
        })
    }

    /** Long-press timeout: 200ms is enough to separate a press from a drag. */
    private val longPressTimeout = 200L
    private val touchSlop = ViewConfiguration.get(recyclerView.context).scaledTouchSlop
    /** Width of the activation strip at the list's right edge. */
    private val activationZoneWidth = (40 * recyclerView.resources.displayMetrics.density).toInt()

    private var downX = 0f
    /** Press position (list coordinates) where the long press started. */
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    /** True while the long-press drag owns the gesture. */
    internal var scrollerActive = false
    /** Finger position (list coordinates) of the latest forwarded event. */
    internal var lastFingerY = 0f
    private var thumbInHostOverlay = false

    private val longPressRunnable = Runnable { activateScroller() }

    override fun onInterceptTouchEvent(recyclerView: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isInActivationZone(e.x, e.y)) {
                    downX = e.x
                    downY = e.y
                    lastX = e.x
                    lastY = e.y
                    downTime = e.downTime
                    scrollerActive = false
                    recyclerView.postDelayed(longPressRunnable, longPressTimeout)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrollerActive) return true
                // Not activated: keep the touch flowing through untouched and
                // only cancel the long-press watch once the finger actually
                // moves beyond the touch slop (i.e. the user is scrolling).
                lastX = e.x
                lastY = e.y
                if (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop) {
                    recyclerView.removeCallbacks(longPressRunnable)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrollerActive) return true
                recyclerView.removeCallbacks(longPressRunnable)
            }
        }
        return false
    }

    override fun onTouchEvent(recyclerView: RecyclerView, e: MotionEvent) {
        if (!scrollerActive) return
        if (e.actionMasked == MotionEvent.ACTION_MOVE) {
            lastFingerY = e.y
        }
        fastScrollPredicate?.test(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            scrollerActive = false
        }
    }

    /**
     * The activation region is a fixed-width strip at the list's right edge.
     * Anything outside of it is not handled by the fast scroller, so it keeps
     * working untouched.
     */
    private fun isInActivationZone(x: Float, y: Float): Boolean {
        if (!recyclerView.isAttachedToWindow) return false
        val right = recyclerView.width - recyclerView.paddingRight
        if (x > right || x < right - activationZoneWidth) return false
        return y >= recyclerView.paddingTop && y <= recyclerView.height - recyclerView.paddingBottom
    }

    /**
     * Keeps the thumb drawn on top of the bottom bars. The thumb normally
     * lives in the list's overlay, which draws below the bottom navigation
     * bar and the mini player, so it ends up hidden behind them when the list
     * is scrolled to its end. Re-home it in the root content's overlay —
     * drawn above every bottom bar — and translate its position from the
     * list's coordinates into the root's, leaving the thumb free to track
     * the list all the way down while staying visible.
     */
    fun syncThumbOverlay() {
        val thumb = thumbView ?: return
        if (thumb.height == 0) return
        val host = recyclerView.rootView.findViewById<ViewGroup>(R.id.mainContent) ?: return
        if (!thumbInHostOverlay) {
            recyclerView.overlay.remove(thumb)
            host.overlay.add(thumb)
            thumbInHostOverlay = true
        }
        // The library re-lays the thumb out each draw at the list's right
        // edge; detect that state (list coordinates) and translate it into
        // the host's coordinates. On frames where the library skipped the
        // layout (scrollbar disabled) the thumb keeps the translated
        // position, so only translate when it was just re-laid out.
        val libraryLeft = if (recyclerView.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            recyclerView.paddingLeft
        } else {
            recyclerView.width - recyclerView.paddingRight - thumb.width
        }
        if (thumb.left != libraryLeft) return
        // Keep the thumb inside the list's top padding. The bottom is left
        // free: while dragging, scrollTo() caps the scroll so the thumb stops
        // on the player's upper edge (or the navigation bar's without the
        // player), and normal scrolling tracks the content all the way down.
        if (thumb.top < recyclerView.paddingTop) {
            thumb.offsetTopAndBottom(recyclerView.paddingTop - thumb.top)
        }
        val loc = IntArray(2)
        recyclerView.getLocationInWindow(loc)
        val rvX = loc[0]
        val rvY = loc[1]
        host.getLocationInWindow(loc)
        thumb.offsetLeftAndRight(rvX - loc[0])
        thumb.offsetTopAndBottom(rvY - loc[1])
        val navView = host.findViewById<View>(R.id.navigationView)
        if (navView != null) {
            val navT = navView.top
            // The bottom padding must make the scroll range match the visible
            // area, not the list's own height. Two things shorten it:
            //  - the sheet (mini player) floating over the list's bottom, and
            //  - the app bar pushing the list down when expanded, so the
            //    list's bottom edge dips behind the bottom bars and the last
            //    item can never scroll into view above them.
            val sheet = host.findViewById<View>(R.id.sheet_view)
            val sheetVisTop = if (sheet != null) {
                (sheet.top - sheet.translationY).toInt()
            } else navT
            val playerOverlap = (navT - sheetVisTop).coerceAtLeast(0)
            val appBarGap = (rvY + recyclerView.height - navT).coerceAtLeast(0)
            val newPadB = playerOverlap + appBarGap
            if (recyclerView.paddingBottom != newPadB) {
                recyclerView.setPadding(
                    recyclerView.paddingLeft,
                    recyclerView.paddingTop,
                    recyclerView.paddingRight,
                    newPadB
                )
            }
            // The list shifts briefly while the app bar animates; do not let
            // that push the thumb into the bottom bars. With the padding
            // above, the thumb's real bottom never exceeds the navigation
            // bar's upper edge anyway, so this only corrects the transient
            // shift.
            if (thumb.bottom > navT) {
                thumb.offsetTopAndBottom(navT - thumb.bottom)
            }
        }
    }

    private fun activateScroller() {
        if (scrollerActive || !recyclerView.isAttachedToWindow) return
        // The finger is resting on the handle region: take the gesture over.
        // First cancel the list's current touch (dropping any pressed state
        // on the item under the finger) while this listener still passes
        // events through, so the cancel reaches the list content.
        val cancel = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, lastX, lastY, 0
        )
        recyclerView.dispatchTouchEvent(cancel)
        cancel.recycle()
        // The library only starts a drag on a visible thumb (alpha > 0), and
        // the thumb fades out when the list is idle.
        thumbView?.alpha = 1f
        scrollerActive = true
        lastFingerY = lastY
        // Feed the scroller a synthetic DOWN at the current touch position so
        // it enters its drag state; the real events that follow are
        // intercepted and forwarded to it.
        val down = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, lastX, lastY, 0
        )
        fastScrollPredicate?.test(down)
        down.recycle()
    }
}

/**
 * Potentially animate showing a [BottomNavigationView].
 *
 * Abruptly changing the visibility leads to a re-layout of main content, animating
 * `translationY` leaves a gap where the view was that content does not fill.
 *
 * Instead, take a snapshot of the view, and animate this in, only changing the visibility (and
 * thus layout) when the animation completes.
 */
fun NavigationBarView.show() {
    if (this is NavigationRailView) return
    if (isVisible) return

    val parent = parent as ViewGroup
    // View needs to be laid out to create a snapshot & know position to animate. If view isn't
    // laid out yet, need to do this manually.
    if (!isLaidOut) {
        measure(
            View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.AT_MOST)
        )
        layout(parent.left, parent.height - measuredHeight, parent.right, parent.height)
    }

    val drawable = drawToBitmap().toDrawable(context.resources)
    drawable.setBounds(left, parent.height, right, parent.height + height)
    parent.overlay.add(drawable)
    ValueAnimator.ofInt(parent.height, top).apply {
        duration = BOOMING_ANIM_TIME
        interpolator = AnimationUtils.loadInterpolator(context, android.R.interpolator.accelerate_decelerate)
        addUpdateListener {
            val newTop = it.animatedValue as Int
            drawable.setBounds(left, newTop, right, newTop + height)
        }
        doOnEnd {
            parent.overlay.remove(drawable)
            isVisible = true
        }
        start()
    }
}

/**
 * Potentially animate hiding a [BottomNavigationView].
 *
 * Abruptly changing the visibility leads to a re-layout of main content, animating
 * `translationY` leaves a gap where the view was that content does not fill.
 *
 * Instead, take a snapshot, instantly hide the view (so content lays out to fill), then animate
 * out the snapshot.
 */
fun NavigationBarView.hide() {
    if (this is NavigationRailView) return
    if (isGone) return

    if (!isLaidOut) {
        isGone = true
        return
    }

    val drawable = drawToBitmap().toDrawable(context.resources)
    val parent = parent as ViewGroup
    drawable.setBounds(left, top, right, bottom)
    parent.overlay.add(drawable)
    isGone = true
    ValueAnimator.ofInt(top, parent.height).apply {
        duration = BOOMING_ANIM_TIME
        interpolator = AnimationUtils.loadInterpolator(context, android.R.interpolator.accelerate_decelerate)
        addUpdateListener {
            val newTop = it.animatedValue as Int
            drawable.setBounds(left, newTop, right, newTop + height)
        }
        doOnEnd {
            parent.overlay.remove(drawable)
        }
        start()
    }
}

fun BaseProgressIndicator<*>.setWavy(isWavy: Boolean) {
    val oldAmplitude = waveAmplitude
    val newAmplitude = if (!isWavy) 0 else {
        resources.getDimensionPixelSize(
            M3R.dimen.m3_comp_progress_indicator_circular_active_indicator_wave_amplitude
        )
    }

    val waveLength = if (isIndeterminate) wavelengthIndeterminate else wavelengthDeterminate

    (getTag(R.id.id_wave_amplitude_animator) as? ValueAnimator)?.apply {
        removeAllUpdateListeners()
        removeAllListeners()
        cancel()
    }
    setTag(R.id.id_wave_amplitude_animator, null)

    if (waveLength <= 0 || oldAmplitude == newAmplitude) {
        waveAmplitude = newAmplitude
        return
    }

    val animator = ValueAnimator.ofInt(oldAmplitude, newAmplitude).apply {
        duration = BOOMING_ANIM_TIME
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            waveAmplitude = it.animatedValue as Int
        }
    }

    setTag(R.id.id_wave_amplitude_animator, animator)
    animator.start()
}

fun BaseProgressIndicator<*>.setAnimatedWave(isAnimatedWave: Boolean) {
    waveSpeed = if (isAnimatedWave) {
        resources.getDimensionPixelSize(R.dimen.m3e_progress_indicator_animation_speed)
    } else 0
}

fun BaseProgressIndicator<*>.installWavyAnimatorCleanup() {
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewDetachedFromWindow(v: View) {
            (getTag(R.id.id_wave_amplitude_animator) as? ValueAnimator)?.apply {
                removeAllListeners()
                removeAllUpdateListeners()
                cancel()
            }
            setTag(R.id.id_wave_amplitude_animator, null)
            removeOnAttachStateChangeListener(this)
        }

        override fun onViewAttachedToWindow(v: View) = Unit
    })
}

typealias TrackingTouchListener = (Slider) -> Unit

fun Slider.setTrackingTouchListener(
    onStart: TrackingTouchListener? = null,
    onStop: TrackingTouchListener? = null
) {
    if (onStart == null && onStop == null)
        return

    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) {
            onStart?.invoke(slider)
        }

        override fun onStopTrackingTouch(slider: Slider) {
            onStop?.invoke(slider)
        }
    })
}

inline fun Context.createBoomingMusicBalloon(
    tooltipId: String,
    lifecycleOwner: LifecycleOwner,
    crossinline block: Balloon.Builder.() -> Unit
): Balloon {
    val bgColor = resolveColor(M3R.attr.colorTertiaryContainer)
    val textColor = resolveColor(M3R.attr.colorOnTertiaryContainer)
    return createBalloon(this) {
        setBackgroundColor(bgColor)
        setTextColor(textColor)
        setWidthRatio(0.8f)
        setPadding(10)
        setHeight(BalloonSizeSpec.WRAP)
        setBalloonAnimation(BalloonAnimation.CIRCULAR)
        setArrowPositionRules(ArrowPositionRules.ALIGN_ANCHOR)
        setArrowPosition(0.5f)
        setCornerRadiusResource(R.dimen.m3_card_corner_radius)
        setDismissWhenTouchOutside(true)
        setAutoDismissDuration(5000)
        setLifecycleOwner(lifecycleOwner)
        setPreferenceName(tooltipId)
        block(this)
    }
}

fun BottomSheetBehavior<*>.peekHeightAnimate(value: Int): Animator {
    return ObjectAnimator.ofInt(this, "peekHeight", value).apply {
        duration = BOOMING_ANIM_TIME
        start()
    }
}

fun AppBarLayout.setupStatusBarForeground() {
    val drawable = MaterialShapeDrawable.createWithElevationOverlay(context)
    // Screens that follow the cover ask for this strip every time they come
    // back, and the drawable they get is painted with the theme colour of that
    // very moment: tinted here with the palette already published, so the strip
    // never has to wait for the next song change to catch up.
    CoverColorState.scheme.value
        ?.takeIf { CoverColorState.isEnabled }
        ?.forNightMode(resources.isNightMode)
        ?.let { drawable.setTint(it.surface.toArgb()) }
    statusBarForeground = drawable
}

fun CompoundButton.animateToggle() = post { isChecked = !isChecked }