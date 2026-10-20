package com.shaterguy.opentext;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import java.lang.reflect.Field;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class FlingEditTextTest {
    private FlingEditText editor;
    private long down;

    @Before public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        editor = new FlingEditText(activity);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setPadding(12, 12, 12, 12);
        editor.setTextSize(16);
        editor.setText("line of editable text\n".repeat(300));
        activity.setContentView(editor);
        editor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        editor.layout(0, 0, 400, 600);
        editor.requestFocus();
        editor.setSelection(0);
        editor.scrollTo(0, 0);
        down = SystemClock.uptimeMillis();
    }

    private void touch(int action, float y, long elapsed) {
        MotionEvent event = MotionEvent.obtain(down, down + elapsed, action, 180, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        editor.onTouchEvent(event);
        event.recycle();
    }

    private void drag() {
        touch(MotionEvent.ACTION_DOWN, 500, 0);
        touch(MotionEvent.ACTION_MOVE, 450, 16);
        touch(MotionEvent.ACTION_MOVE, 350, 32);
        touch(MotionEvent.ACTION_MOVE, 230, 48);
    }

    private OverScroller fling() throws Exception {
        Field field = FlingEditText.class.getDeclaredField("fling");
        field.setAccessible(true);
        return (OverScroller) field.get(editor);
    }

    @Test public void fastNativeDragContinuesAfterReleaseAndStopsWithinDocument() throws Exception {
        String original = editor.getText().toString();
        drag();
        int beforeUp = editor.getScrollY();
        assertTrue("Native drag must scroll before testing inertia", beforeUp > 0);
        touch(MotionEvent.ACTION_UP, 190, 64);
        assertFalse("Fast drag should start inertia", fling().isFinished());
        ShadowSystemClock.advanceBy(Duration.ofMillis(48));
        editor.computeScroll();
        assertTrue("Scroll should continue after finger release", editor.getScrollY() > beforeUp);
        for (int i = 0; i < 500 && !fling().isFinished(); i++) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16));
            editor.computeScroll();
        }
        assertTrue("Inertia must terminate", fling().isFinished());
        int max = Math.max(0, editor.getLayout().getHeight()
                - (editor.getHeight() - editor.getTotalPaddingTop() - editor.getTotalPaddingBottom()));
        assertTrue(editor.getScrollY() >= 0 && editor.getScrollY() <= max);
        assertEquals(original, editor.getText().toString());
    }

    @Test public void tapDoesNotFlingAndEditingStillWorks() throws Exception {
        touch(MotionEvent.ACTION_DOWN, 100, 0);
        touch(MotionEvent.ACTION_UP, 100, 40);
        assertTrue(fling().isFinished());
        editor.setSelection(2);
        editor.getText().insert(2, "X");
        assertEquals('X', editor.getText().charAt(2));
    }

    @Test public void cancelNeverStartsInertia() throws Exception {
        drag();
        touch(MotionEvent.ACTION_CANCEL, 190, 64);
        assertTrue(fling().isFinished());
    }

    @Test public void selectedTextNeverStartsInertia() throws Exception {
        editor.setSelection(0, 4);
        drag();
        touch(MotionEvent.ACTION_UP, 190, 64);
        assertTrue(fling().isFinished());
    }

    @Test public void longPressNeverStartsInertia() throws Exception {
        drag();
        editor.performLongClick();
        touch(MotionEvent.ACTION_UP, 190, 64);
        assertTrue(fling().isFinished());
    }

    @Test public void newTouchStopsInertia() throws Exception {
        drag();
        touch(MotionEvent.ACTION_UP, 190, 64);
        assertFalse(fling().isFinished());
        down += 80;
        touch(MotionEvent.ACTION_DOWN, 300, 0);
        assertTrue(fling().isFinished());
    }

    @Test public void textChangeAndSelectionStopInertia() throws Exception {
        drag();
        touch(MotionEvent.ACTION_UP, 190, 64);
        assertFalse(fling().isFinished());
        editor.getText().append("new text");
        assertTrue(fling().isFinished());
        down += 100;
        drag();
        touch(MotionEvent.ACTION_UP, 190, 64);
        editor.setSelection(0, 3);
        assertTrue(fling().isFinished());
    }

    @Test public void shortDocumentDoesNotFling() throws Exception {
        editor.setText("short");
        editor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        editor.layout(0, 0, 400, 600);
        drag();
        touch(MotionEvent.ACTION_UP, 190, 64);
        assertTrue(fling().isFinished());
        assertEquals(0, editor.getScrollY());
    }
    @Test public void slowDragDoesNotStartInertia() throws Exception {
        touch(MotionEvent.ACTION_DOWN, 500, 0);
        touch(MotionEvent.ACTION_MOVE, 450, 1000);
        touch(MotionEvent.ACTION_MOVE, 449, 2000);
        touch(MotionEvent.ACTION_UP, 449, 2100);
        assertTrue(fling().isFinished());
    }

    @Test public void multiplePointersCancelInertiaEligibility() throws Exception {
        drag();
        MotionEvent.PointerProperties first = new MotionEvent.PointerProperties();
        first.id = 0;
        first.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerProperties second = new MotionEvent.PointerProperties();
        second.id = 1;
        second.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords a = new MotionEvent.PointerCoords();
        a.x = 180; a.y = 230; a.pressure = 1; a.size = 1;
        MotionEvent.PointerCoords b = new MotionEvent.PointerCoords();
        b.x = 220; b.y = 250; b.pressure = 1; b.size = 1;
        MotionEvent pointers = MotionEvent.obtain(down, down + 50,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, new MotionEvent.PointerProperties[]{first, second},
                new MotionEvent.PointerCoords[]{a, b}, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        editor.onTouchEvent(pointers);
        pointers.recycle();
        touch(MotionEvent.ACTION_UP, 190, 64);
        assertTrue(fling().isFinished());
    }

    @Test public void resizeStopsInertia() throws Exception {
        drag();
        touch(MotionEvent.ACTION_UP, 190, 64);
        assertFalse(fling().isFinished());
        editor.layout(0, 0, 400, 400);
        assertTrue(fling().isFinished());
    }

    @Test public void downwardFingerFlingMovesUpAndStopsAtTop() throws Exception {
        editor.scrollTo(0, 600);
        touch(MotionEvent.ACTION_DOWN, 100, 0);
        touch(MotionEvent.ACTION_MOVE, 150, 16);
        touch(MotionEvent.ACTION_MOVE, 250, 32);
        touch(MotionEvent.ACTION_MOVE, 370, 48);
        int beforeUp = editor.getScrollY();
        assertTrue(beforeUp < 600 && beforeUp > 0);
        touch(MotionEvent.ACTION_UP, 410, 64);
        assertFalse(fling().isFinished());
        ShadowSystemClock.advanceBy(Duration.ofMillis(48));
        editor.computeScroll();
        assertTrue(editor.getScrollY() < beforeUp);
        for (int i = 0; i < 500 && !fling().isFinished(); i++) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16));
            editor.computeScroll();
        }
        assertTrue(fling().isFinished());
        assertEquals(0, editor.getScrollY());
    }

}
