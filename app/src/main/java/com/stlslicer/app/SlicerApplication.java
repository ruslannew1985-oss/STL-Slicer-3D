package com.stlslicer.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/**
 * Keeps page scrolling from stealing touch gestures while the user is
 * rotating, zooming or positioning a model in the interactive viewers.
 */
public class SlicerApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                View decor = activity.getWindow().getDecorView();
                decor.post(() -> installTouchLocks(decor));
            }

            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private void installTouchLocks(View view) {
        if (isInteractiveModelView(view)) {
            view.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                boolean keepGesture = action != MotionEvent.ACTION_UP &&
                        action != MotionEvent.ACTION_CANCEL;

                ViewParent parent = v.getParent();
                if (parent != null) {
                    // This propagates up through the ScrollView hierarchy.
                    parent.requestDisallowInterceptTouchEvent(keepGesture);
                }

                // Return false so the viewer still receives the same event in
                // its own onTouchEvent() and can rotate/zoom/move the model.
                return false;
            });
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                installTouchLocks(group.getChildAt(i));
            }
        }
    }

    private boolean isInteractiveModelView(View view) {
        if (view instanceof Gcode3DViewer) return true;
        if (view instanceof STLViewer) return true;

        // Keep compatibility with the older/auxiliary bed placement view if it
        // is present in the hierarchy in a future screen version.
        return "BedPlacementView".equals(view.getClass().getSimpleName());
    }
}
