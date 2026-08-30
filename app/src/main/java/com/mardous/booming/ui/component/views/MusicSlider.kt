package com.mardous.booming.ui.component.views

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.core.content.withStyledAttributes
import com.google.android.material.slider.Slider
import com.mardous.booming.R
import com.mardous.booming.extensions.resources.applyColor
import com.mardous.booming.util.Preferences

/**
 * @author Christians M.A. (mardous)
 */
class MusicSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var listener: Listener? = null
    private var internalView: View? = null
    val progressView get() = internalView

    private val seekBar get() = internalView as? SeekBar
    private val slider get() = internalView as? Slider

    private var thumbHeight = -1
    private var trackHeight = -1
    private var useSquiggly: Boolean = false
    private var useBarStyle: Boolean = false

    var isTrackingTouch: Boolean = false
        private set

    var currentColor: Int
        set(value) {
            seekBar?.applyColor(value)
            slider?.applyColor(value)
        }
        get() = seekBar?.progressTintList?.defaultColor
            ?: slider?.trackActiveTintList?.defaultColor
            ?: Color.TRANSPARENT

    var animateSquigglyProgress: Boolean
        get() = (seekBar?.progressDrawable as? SquigglyProgress)?.animate == true
        set(value) {
            (seekBar?.progressDrawable as? SquigglyProgress)?.animate = value
        }

    var valueFrom: Int
        set(valueFrom) {
            seekBar?.min = valueFrom
            slider?.valueFrom = valueFrom.toFloat()
        }
        get() = seekBar?.min ?: slider?.valueFrom?.toInt() ?: 0

    var valueTo: Int
        set(valueTo) {
            seekBar?.max = valueTo
            slider?.valueTo = valueTo.toFloat().coerceAtLeast(1f)
        }
        get() = seekBar?.max ?: slider?.valueTo?.toInt() ?: 0

    var value: Int
        set(value) {
            seekBar?.progress = value
            slider?.let { slider ->
                slider.value = value.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
            }
        }
        get() = seekBar?.progress ?: slider?.value?.toInt() ?: 0

    init {
        context.withStyledAttributes(attrs, R.styleable.MusicSlider) {
            thumbHeight = getDimensionPixelSize(R.styleable.MusicSlider_musicSliderThumbHeight, -1)
            trackHeight = getDimensionPixelSize(R.styleable.MusicSlider_musicSliderTrackHeight, -1)
            useBarStyle = Preferences.barSeekBar
            useSquiggly = getBoolean(R.styleable.MusicSlider_squigglyStyle, Preferences.squigglySeekBar)
            inflateSliderView(
                useBarStyle = useBarStyle,
                useSquiggly = useSquiggly,
                previousState = ProgressViewState.from(this)
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        detachInternalView()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setUseSquiggly(useSquiggly: Boolean) {
        if (useSquiggly != this.useSquiggly) {
            val previousState = detachInternalView()
            inflateSliderView(useBarStyle, useSquiggly, previousState)
        }
        this.useSquiggly = useSquiggly
    }

    fun setUseBarStyle(barStyle: Boolean) {
        if (barStyle != this.useBarStyle) {
            val previousState = detachInternalView()
            inflateSliderView(barStyle, useSquiggly, previousState)
        }
        this.useBarStyle = barStyle
    }

    private fun detachInternalView(): ProgressViewState {
        val state = ProgressViewState.from(this)
        removeAllViews()
        slider?.clearOnChangeListeners()
        slider?.clearOnSliderTouchListeners()
        seekBar?.setOnSeekBarChangeListener(null)
        internalView = null
        return state
    }

    private fun inflateSliderView(
        useBarStyle: Boolean,
        useSquiggly: Boolean,
        previousState: ProgressViewState?
    ) {
        // 条形样式优先于波浪样式:条形同样基于 Material Slider,
        // 只是把 thumb 从竖条换成小圆点
        internalView = if (useSquiggly && !useBarStyle) {
            LayoutInflater.from(context).inflate(R.layout.music_squiggly_slider, this, false)
        } else {
            LayoutInflater.from(context).inflate(R.layout.music_progress_slider, this, false)
        }
        (internalView as? Slider)?.let {
            if (useBarStyle) {
                // 条形样式:4dp 细轨道 + 8dp 圆点(忽略布局提供的 thumb/track 尺寸,
                // 保证所有播放页观感一致)
                val density = resources.displayMetrics.density
                it.trackHeight = (4 * density).toInt()
                it.thumbWidth = (8 * density).toInt()
                it.thumbHeight = (8 * density).toInt()
                // 移除 M3 Slider 默认在轨道末端绘制的 stop indicator 小圆点
                it.trackStopIndicatorSize = 0
            } else {
                if (thumbHeight != -1) {
                    it.thumbHeight = thumbHeight
                }
                if (trackHeight != -1) {
                    it.trackHeight = trackHeight
                }
            }
        }
        if (previousState != null) {
            this.valueFrom = previousState.min
            this.valueTo = previousState.max
            this.value = previousState.progress
            if (previousState.currentColor != Color.TRANSPARENT) {
                this.currentColor = previousState.currentColor
            }
        }
        addView(internalView)
        setupInternalListener()
    }

    private fun setupInternalListener() {
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                listener?.onProgressChanged(this@MusicSlider, progress, fromUser)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isTrackingTouch = true
                listener?.onStartTrackingTouch(this@MusicSlider)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isTrackingTouch = false
                listener?.onStopTrackingTouch(this@MusicSlider)
            }
        })
        slider?.addOnChangeListener { slider, value, fromUser ->
            listener?.onProgressChanged(this, value.toInt(), fromUser)
        }
        slider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isTrackingTouch = true
                listener?.onStartTrackingTouch(this@MusicSlider)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                isTrackingTouch = false
                listener?.onStopTrackingTouch(this@MusicSlider)
            }
        })
    }

    interface Listener {
        fun onProgressChanged(slider: MusicSlider, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(slider: MusicSlider)
        fun onStopTrackingTouch(slider: MusicSlider)
    }

    private class ProgressViewState(
        val max: Int,
        val min: Int,
        val progress: Int,
        val currentColor: Int = Color.TRANSPARENT
    ) {
        companion object {
            fun from(slider: MusicSlider) = ProgressViewState(
                max = slider.valueTo,
                min = slider.valueFrom,
                progress = slider.value,
                currentColor = slider.currentColor
            )

            fun from(a: TypedArray) = ProgressViewState(
                max = a.getInt(R.styleable.MusicSlider_android_max, 100),
                min = a.getInt(R.styleable.MusicSlider_android_min, 0),
                progress = a.getInt(R.styleable.MusicSlider_android_progress, 0)
            )
        }
    }
}
