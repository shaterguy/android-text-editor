package com.shaterguy.opentext;

import android.content.Context;
import android.graphics.Rect;
import android.text.Layout;
import android.text.method.Touch;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.OverScroller;

/** Adds inertia after native EditText scrolling, without intercepting editing gestures. */
public final class FlingEditText extends EditText {
    private OverScroller fling;
    private VelocityTracker velocity;
    private final int minimumVelocity;
    private final int maximumVelocity;
    private boolean eligible;
    private boolean movedY;
    private boolean handlingMove;

    public FlingEditText(Context context) {
        super(context);
        fling = new OverScroller(context);
        ViewConfiguration config = ViewConfiguration.get(context);
        minimumVelocity = config.getScaledMinimumFlingVelocity();
        maximumVelocity = config.getScaledMaximumFlingVelocity();
        setVerticalScrollBarEnabled(true);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stopFling();
            recycleVelocity();
            eligible = isEnabled() && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    && !hasSelection();
            movedY = false;
            velocity = VelocityTracker.obtain();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_CANCEL) {
            eligible = false;
        }
        if (velocity != null) velocity.addMovement(event);
        int oldY = getScrollY();
        // Native handling owns tap, double tap, selection, handles, IME, and dragging.
        handlingMove = action == MotionEvent.ACTION_MOVE;
        boolean handled = super.onTouchEvent(event);
        handlingMove = false;
        if (action == MotionEvent.ACTION_MOVE) {
            movedY |= oldY != getScrollY();
        }
        if (hasSelection()) eligible = false;
        if (action == MotionEvent.ACTION_UP) {
            if (handled && eligible && velocity != null && movedY) {
                velocity.computeCurrentVelocity(1000, maximumVelocity);
                startFling(-velocity.getYVelocity());
            }
            eligible = false;
            recycleVelocity();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            recycleVelocity();
        }
        return handled;
    }

    private void startFling(float y) {
        Layout layout = getLayout();
        if (layout == null) return;
        int vy = Math.abs(y) >= minimumVelocity ? Math.round(y) : 0;
        if (vy == 0) return;
        // Only vertical inertia is added; horizontal dragging remains native.
        fling.fling(0, getScrollY(), 0, vy, 0, 0, 0, maxScrollY());
        postInvalidateOnAnimation();
    }

    private int maxScrollY() {
        Layout layout = getLayout();
        return layout == null ? 0 : Math.max(0, layout.getHeight()
                - (getHeight() - getTotalPaddingTop() - getTotalPaddingBottom()));
    }

    @Override public void computeScroll() {
        if (fling == null || fling.isFinished()) {
            super.computeScroll();
            return;
        }
        Layout layout = getLayout();
        if (!isEnabled() || layout == null || hasSelection()) {
            stopFling();
            return;
        }
        if (fling.computeScrollOffset()) {
            int oldY = getScrollY();
            Touch.scrollTo(this, layout, getScrollX(),
                    Math.max(0, Math.min(maxScrollY(), fling.getCurrY())));
            if (oldY == getScrollY() && fling.getCurrY() != oldY) {
                stopFling();
            } else {
                postInvalidateOnAnimation();
            }
        }
    }

    public void stopFling() {
        // TextView invokes some overridden callbacks during its constructor.
        if (fling != null) fling.forceFinished(true);
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    @Override public boolean performLongClick() {
        eligible = false;
        stopFling();
        return super.performLongClick();
    }

    @Override protected void onSelectionChanged(int start, int end) {
        stopFling();
        if (start != end || handlingMove) eligible = false;
        super.onSelectionChanged(start, end);
    }

    @Override protected void onTextChanged(CharSequence text, int start, int before, int count) {
        stopFling();
        eligible = false;
        super.onTextChanged(text, start, before, count);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        stopFling();
        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override protected void onFocusChanged(boolean focused, int direction, Rect rect) {
        if (!focused) stopFling();
        super.onFocusChanged(focused, direction, rect);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        if (!focused) stopFling();
        super.onWindowFocusChanged(focused);
    }

    @Override protected void onDetachedFromWindow() {
        stopFling();
        recycleVelocity();
        super.onDetachedFromWindow();
    }
}
