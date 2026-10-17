package com.samourai.sentinel.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.samourai.sentinel.R;
import com.samourai.sentinel.data.entropy.EntropyBand;


/**
 * #115 band ladder. Five rungs; filled rungs = min(nbCmbn, 5),
 * shortest-first (a full green ladder lights the tallest rung);
 * rung color = the EntropyBands tier. Grey = no verdict: track
 * only, nothing filled (declined / unparseable rows).
 *
 * Retires the pre-#115 semantics - fill = non-deterministic link
 * ratio, green-only - which read as a privacy score it never was
 * (a 1-bit tx rendered 2 green bars). The dead ratio overload
 * setRange(TxProcessorResult) went with it (census: zero call
 * sites); this widget no longer imports the vendored engine.
 */
public class EntropyBar extends View {

    private static final int RUNGS = 5;

    private Paint mRungTrack, mRungRed, mRungAmber, mRungGreen;
    private int maxBars = RUNGS;
    private int filledBars = 0;
    private EntropyBand band = EntropyBand.GREY;
    private int mBarWidth = 0;
    private int mBarHeight = 0;

    public EntropyBar(Context context) {
        super(context);
        init();
    }

    public EntropyBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EntropyBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mRungTrack = fillPaint(R.color.disabled_grey);
        mRungRed = fillPaint(R.color.red);
        mRungAmber = fillPaint(R.color.md_amber_500);
        mRungGreen = fillPaint(R.color.green_ui_2);
        mBarWidth = (getWidth() / maxBars) - (mBarWidth * maxBars);
        mBarHeight = (getHeight() / maxBars);
    }

    private Paint fillPaint(int colorRes) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(ContextCompat.getColor(getContext(), colorRes));
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);
        return paint;
    }

    /**
     * No verdict (engine declined / unparseable): grey track, zero
     * rungs. The row stays refusal-plus-a-pointer, never a number.
     */
    public void setDeclined() {
        band = EntropyBand.GREY;
        filledBars = 0;
        invalidate();
    }

    /**
     * Ladder state: rung color = EntropyBands tier, filled rungs =
     * min(nbCmbn, RUNGS). Clamped here as a guard; EntropyBands
     * .filled already clamps.
     */
    public void setState(EntropyBand band, int filledBars) {
        this.band = band;
        this.filledBars = Math.max(0, Math.min(maxBars, filledBars));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w != oldw || h != oldh) {
            mBarWidth = (w / maxBars) - (mBarWidth * maxBars);
            mBarHeight = (h / maxBars);
        }
        super.onSizeChanged(w, h, oldw, oldh);
    }


    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < maxBars; i++) {
            int mBarMargin = 4;
            int left = getWidth() - ((mBarWidth * i) + mBarMargin);
            int right = getWidth() - (mBarWidth * (i + 1));
            int top = (mBarHeight * i) + 6;
            // i counts from the right (tallest) rung; the lit set is
            // the leftmost filledBars rungs - same fill direction as
            // the old enabled/disabled split, so green = full ladder.
            Paint paint = i >= (maxBars - filledBars) ? bandPaint() : mRungTrack;
            canvas.drawRoundRect(left, getHeight(), right, top, 9f, 9f, paint);
        }
    }

    private Paint bandPaint() {
        switch (band) {
            case RED:
                return mRungRed;
            case AMBER:
                return mRungAmber;
            case GREEN:
                return mRungGreen;
            case GREY:
            default:
                return mRungTrack;
        }
    }
}
