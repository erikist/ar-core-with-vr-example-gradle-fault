/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecreativelab.drawar;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.googlecreativelab.drawar.rendering.BackgroundRenderer;
import com.googlecreativelab.drawar.rendering.LineShaderRenderer;
import com.googlecreativelab.drawar.rendering.LineUtils;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.vecmath.Vector2f;
import javax.vecmath.Vector3f;


/**
 * This is a complex example that shows how to create an augmented reality (AR) application using
 * the ARCore API.
 */

public class DrawAR extends AppCompatActivity implements GLSurfaceView.Renderer, GestureDetector.OnGestureListener,
        GestureDetector.OnDoubleTapListener{
    private static final String TAG = DrawAR.class.getSimpleName();

    private GLSurfaceView surfaceView;

    private Config mDefaultConfig;
    private Session session;
    private BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private LineShaderRenderer lineShaderRenderer = new LineShaderRenderer();
    private Frame frame;

    private float[] projectionMatrix = new float[16];
    private float[] viewMatrix = new float[16];
    private float[] zeroMatrix = new float[16];

    private boolean isPaused = false;

    private float screenWidth = 0;
    private float screenHeight = 0;

    private BiquadFilter biquadFilter;
    private Vector3f lastPoint;
    private AtomicReference<Vector2f> lastTouch = new AtomicReference<>();

    private GestureDetectorCompat gestureDetector;

    private LinearLayout mSettingsUI;
    private LinearLayout mButtonBar;

    private SeekBar mLineWidthBar;
    private SeekBar mLineDistanceScaleBar;
    private SeekBar mSmoothingBar;


    private float lineWidth = 0.33f;
    private float distanceScale = 0.0f;
    private float lineSmoothing = 0.1f;

    private float[] lastFramePosition;

    private AtomicBoolean isTracking = new AtomicBoolean(true);
    private AtomicBoolean recenterView = new AtomicBoolean(false);
    private AtomicBoolean isTouchDown = new AtomicBoolean(false);
    private AtomicBoolean shouldClearDrawing = new AtomicBoolean(false);
    private AtomicBoolean lineParameters = new AtomicBoolean(false);
    private AtomicBoolean undo = new AtomicBoolean(false);
    private AtomicBoolean newStroke = new AtomicBoolean(false);

    private ArrayList<ArrayList<Vector3f>> strokes;

    private DisplayRotationHelper displayRotationHelper;
    private Snackbar mMessageSnackbar;

    private boolean installRequested;

    private TrackingState trackingState;
    /**
     * Setup the app when main activity is created
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);

        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surfaceview);
        mSettingsUI = findViewById(R.id.strokeUI);
        mButtonBar = findViewById(R.id.button_bar);

        // Settings seek bars
        mLineDistanceScaleBar = findViewById(R.id.distanceScale);
        mLineWidthBar = findViewById(R.id.lineWidth);
        mSmoothingBar = findViewById(R.id.smoothingSeekBar);

        mLineDistanceScaleBar.setProgress(sharedPref.getInt("mLineDistanceScale", 1));
        mLineWidthBar.setProgress(sharedPref.getInt("mLineWidth", 10));
        mSmoothingBar.setProgress(sharedPref.getInt("mSmoothing", 50));

        distanceScale = LineUtils.map((float) mLineDistanceScaleBar.getProgress(), 0, 100, 1, 200, true);
        lineWidth = LineUtils.map((float) mLineWidthBar.getProgress(), 0f, 100f, 0.1f, 5f, true);
        lineSmoothing = LineUtils.map((float) mSmoothingBar.getProgress(), 0, 100, 0.01f, 0.2f, true);

        SeekBar.OnSeekBarChangeListener seekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
            /**
             * Listen for seekbar changes, and update the settings
             */
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                SharedPreferences.Editor editor = sharedPref.edit();

                if (seekBar == mLineDistanceScaleBar) {
                    editor.putInt("mLineDistanceScale", progress);
                    distanceScale = LineUtils.map((float) progress, 0f, 100f, 1f, 200f, true);
                } else if (seekBar == mLineWidthBar) {
                    editor.putInt("mLineWidth", progress);
                    lineWidth = LineUtils.map((float) progress, 0f, 100f, 0.1f, 5f, true);
                } else if (seekBar == mSmoothingBar) {
                    editor.putInt("mSmoothing", progress);
                    lineSmoothing = LineUtils.map((float) progress, 0, 100, 0.01f, 0.2f, true);
                }
                lineShaderRenderer.bNeedsUpdate.set(true);

                editor.apply();

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };

        mLineDistanceScaleBar.setOnSeekBarChangeListener(seekBarChangeListener);
        mLineWidthBar.setOnSeekBarChangeListener(seekBarChangeListener);
        mSmoothingBar.setOnSeekBarChangeListener(seekBarChangeListener);

        // Hide the settings ui
        mSettingsUI.setVisibility(View.GONE);

        displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);
        // Reset the zero matrix
        Matrix.setIdentityM(zeroMatrix, 0);

        lastPoint = new Vector3f(0, 0, 0);

        installRequested = false;

        // Set up renderer.
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // Setup touch detector
        gestureDetector = new GestureDetectorCompat(this, this);
        gestureDetector.setOnDoubleTapListener(this);
        strokes = new ArrayList<>();


    }


    /**
     * addStroke adds a new stroke to the scene
     *
     * @param touchPoint a 2D point in screen space and is projected into 3D world space
     */
    private void addStroke(Vector2f touchPoint) {
        Vector3f newPoint = LineUtils.GetWorldCoords(touchPoint, screenWidth, screenHeight, projectionMatrix, viewMatrix);
        addStroke(newPoint);
    }


    /**
     * addPoint adds a point to the current stroke
     *
     * @param touchPoint a 2D point in screen space and is projected into 3D world space
     */
    private void addPoint(Vector2f touchPoint) {
        Vector3f newPoint = LineUtils.GetWorldCoords(touchPoint, screenWidth, screenHeight, projectionMatrix, viewMatrix);
        addPoint(newPoint);
    }


    /**
     * addStroke creates a new stroke
     *
     * @param newPoint a 3D point in world space
     */
    private void addStroke(Vector3f newPoint) {
        biquadFilter = new BiquadFilter(lineSmoothing);
        for (int i = 0; i < 1500; i++) {
            biquadFilter.update(newPoint);
        }
        Vector3f p = biquadFilter.update(newPoint);
        lastPoint = new Vector3f(p);
        strokes.add(new ArrayList<Vector3f>());
        strokes.get(strokes.size() - 1).add(lastPoint);
    }

    /**
     * addPoint adds a point to the current stroke
     *
     * @param newPoint a 3D point in world space
     */
    private void addPoint(Vector3f newPoint) {
        if (LineUtils.distanceCheck(newPoint, lastPoint)) {
            Vector3f p = biquadFilter.update(newPoint);
            lastPoint = new Vector3f(p);
            strokes.get(strokes.size() - 1).add(lastPoint);
        }
    }


    /**
     * onResume part of the Android Activity Lifecycle
     */
    @Override
    protected void onResume() {
        super.onResume();

        if (session == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!PermissionHelper.hasCameraPermission(this)) {
                    PermissionHelper.requestCameraPermission(this);
                    return;
                }

                session = new Session(/* context= */ this);
            } catch (UnavailableArcoreNotInstalledException
                    | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (Exception e) {
                message = "This device does not support AR";
                exception = e;
            }

            if (message != null) {
                Log.e(TAG, "Exception creating session", exception);
                return;
            }

            // Create default config and check if supported.
            Config config = new Config(session);
            if (!session.isSupported(config)) {
                Log.e(TAG, "Exception creating session Device Does Not Support ARCore", exception);
            }
            session.configure(config);
        }
        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            e.printStackTrace();
        }
        surfaceView.onResume();
        displayRotationHelper.onResume();
        isPaused = false;
    }

    /**
     * onPause part of the Android Activity Lifecycle
     */
    @Override
    public void onPause() {
        super.onPause();
        // Note that the order matters - GLSurfaceView is paused first so that it does not try
        // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
        // still call session.update() and get a SessionPausedException.

        if (session != null) {
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }

        isPaused = false;


        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        screenHeight = displayMetrics.heightPixels;
        screenWidth = displayMetrics.widthPixels;
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (!PermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Standard Android full-screen functionality.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }


    /**
     * Create renderers after the Surface is Created and on the GL Thread
     */
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {

        if (session == null) {
            return;
        }

        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Create the texture and pass it to ARCore session to be filled during update().
        backgroundRenderer.createOnGlThread(/*context=*/this);

        try {

            session.setCameraTextureName(backgroundRenderer.getTextureId());
            lineShaderRenderer.createOnGlThread(this);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.onSurfaceChanged(width, height);
        screenWidth = width;
        screenHeight = height;
    }


    /**
     * update() is executed on the GL Thread.
     * The method handles all operations that need to take place before drawing to the screen.
     * The method :
     * extracts the current projection matrix and view matrix from the AR Pose
     * handles adding stroke and points to the data collections
     * updates the ZeroMatrix and performs the matrix multiplication needed to re-center the drawing
     * updates the Line Renderer with the current strokes, color, distance scale, line width etc
     */
    private void update() {

        if (session == null) {
            return;
        }

        displayRotationHelper.updateSessionIfNeeded(session);

        try {

            session.setCameraTextureName(backgroundRenderer.getTextureId());

            frame = session.update();
            Camera camera = frame.getCamera();

            trackingState = camera.getTrackingState();

            // Update tracking states
            if (trackingState == TrackingState.TRACKING && !isTracking.get()) {
                isTracking.set(true);
            } else if (trackingState == TrackingState.STOPPED && isTracking.get()) {
                isTracking.set(false);
                isTouchDown.set(false);
            }

            // Get projection matrix.
            camera.getProjectionMatrix(projectionMatrix, 0, AppSettings.getNearClip(), AppSettings.getFarClip());
            camera.getViewMatrix(viewMatrix, 0);

            float[] position = new float[3];
            camera.getPose().getTranslation(position, 0);

            // Check if camera has moved much, if thats the case, stop touchDown events
            // (stop drawing lines abruptly through the air)
            if (lastFramePosition != null) {
                Vector3f distance = new Vector3f(position[0], position[1], position[2]);
                distance.sub(new Vector3f(lastFramePosition[0], lastFramePosition[1], lastFramePosition[2]));

                if (distance.length() > 0.15) {
                    isTouchDown.set(false);
                }
            }
            lastFramePosition = position;

            // Multiply the zero matrix
            Matrix.multiplyMM(viewMatrix, 0, viewMatrix, 0, zeroMatrix, 0);


            if (newStroke.get()) {
                newStroke.set(false);
                addStroke(lastTouch.get());
                lineShaderRenderer.bNeedsUpdate.set(true);
            } else if (isTouchDown.get()) {
                addPoint(lastTouch.get());
                lineShaderRenderer.bNeedsUpdate.set(true);
            }

            if (recenterView.get()) {
                recenterView.set(false);
                zeroMatrix = getCalibrationMatrix();
            }

            if (shouldClearDrawing.get()) {
                shouldClearDrawing.set(false);
                clearDrawing();
                lineShaderRenderer.bNeedsUpdate.set(true);
            }

            if (undo.get()) {
                undo.set(false);
                if (strokes.size() > 0) {
                    strokes.remove(strokes.size() - 1);
                    lineShaderRenderer.bNeedsUpdate.set(true);
                }
            }
            lineShaderRenderer.setDrawDebug(lineParameters.get());
            if (lineShaderRenderer.bNeedsUpdate.get()) {
                lineShaderRenderer.setColor(AppSettings.getColor());
                lineShaderRenderer.mDrawDistance = AppSettings.getStrokeDrawDistance();
                lineShaderRenderer.setDistanceScale(distanceScale);
                lineShaderRenderer.setLineWidth(lineWidth);
                lineShaderRenderer.clear();
                lineShaderRenderer.updateStrokes(strokes);
                lineShaderRenderer.upload();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }




    /**
     * GL Thread Loop
     * clears the Color Buffer and Depth Buffer, draws the current texture from the camera
     * and draws the Line Renderer if ARCore is tracking the world around it
     */
    @Override
    public void onDrawFrame(GL10 gl) {
        if (isPaused) return;

        update();

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (frame == null) {
            return;
        }

        // Draw background.
        backgroundRenderer.draw(frame);

        // Draw Lines
        if (frame.getCamera().getTrackingState() == TrackingState.TRACKING) {
            lineShaderRenderer.draw(viewMatrix, projectionMatrix, screenWidth, screenHeight, AppSettings.getNearClip(), AppSettings.getFarClip());
        }
    }


    /**
     * Get a matrix usable for zero calibration (only position and compass direction)
     */
    public float[] getCalibrationMatrix() {
        float[] t = new float[3];
        float[] m = new float[16];

        frame.getCamera().getPose().getTranslation(t, 0);
        float[] z = frame.getCamera().getPose().getZAxis();
        Vector3f zAxis = new Vector3f(z[0], z[1], z[2]);
        zAxis.y = 0;
        zAxis.normalize();

        double rotate = Math.atan2(zAxis.x, zAxis.z);

        Matrix.setIdentityM(m, 0);
        Matrix.translateM(m, 0, t[0], t[1], t[2]);
        Matrix.rotateM(m, 0, (float) Math.toDegrees(rotate), 0, 1, 0);
        return m;
    }


    /**
     * Clears the Datacollection of Strokes and sets the Line Renderer to clear and update itself
     * Designed to be executed on the GL Thread
     */
    public void clearDrawing() {
        strokes.clear();
        lineShaderRenderer.clear();
    }


    /**
     * onClickUndo handles the touch input on the GUI and sets the AtomicBoolean undo to be true
     * the actual undo functionality is executed in the GL Thread
     */
    public void onClickUndo(View button) {
        undo.set(true);
    }

    /**
     * onClickLineDebug toggles the Line Renderer's Debug View on and off. The line renderer will
     * highlight the lines on the same depth plane to allow users to draw things more coherently
     */
    public void onClickLineDebug(View button) {
        lineParameters.set(!lineParameters.get());
    }


    /**
     * onClickSettings toggles showing and hiding the Line Width, Smoothing, and Debug View toggle
     */
    public void onClickSettings(View button) {
        ImageButton settingsButton = findViewById(R.id.settingsButton);

        if (mSettingsUI.getVisibility() == View.GONE) {
            mSettingsUI.setVisibility(View.VISIBLE);
            mLineDistanceScaleBar = findViewById(R.id.distanceScale);
            mLineWidthBar = findViewById(R.id.lineWidth);

            settingsButton.setColorFilter(getResources().getColor(R.color.active));
        } else {
            mSettingsUI.setVisibility(View.GONE);
            settingsButton.setColorFilter(getResources().getColor(R.color.gray));
        }
    }

    /**
     * onClickClear handle showing an AlertDialog to clear the drawing
     */
    public void onClickClear(View button) {

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setMessage("Sure you want to clear?");

        // Set up the buttons
        builder.setPositiveButton("Clear ", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                shouldClearDrawing.set(true);
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }


    /**
     * onClickRecenter handles the touch input on the GUI and sets the AtomicBoolean bReCEnterView to be true
     * the actual recenter functionality is executed on the GL Thread
     */
    public void onClickRecenter(View button) {
        recenterView.set(true);
    }

    // ------- Touch events

    /**
     * onTouchEvent handles saving the lastTouch screen position and setting isTouchDown and newStroke
     * AtomicBooleans to trigger addPoint and addStroke on the GL Thread to be called
     */
    @Override
    public boolean onTouchEvent(MotionEvent tap) {
        this.gestureDetector.onTouchEvent(tap);

        if (tap.getAction() == MotionEvent.ACTION_DOWN ) {
            lastTouch.set(new Vector2f(tap.getX(), tap.getY()));
            isTouchDown.set(true);
            newStroke.set(true);
            return true;
        } else if (tap.getAction() == MotionEvent.ACTION_MOVE || tap.getAction() == MotionEvent.ACTION_POINTER_DOWN) {
            lastTouch.set(new Vector2f(tap.getX(), tap.getY()));
            isTouchDown.set(true);
            return true;
        } else if (tap.getAction() == MotionEvent.ACTION_UP || tap.getAction() == MotionEvent.ACTION_CANCEL) {
            isTouchDown.set(false);
            lastTouch.set(new Vector2f(tap.getX(), tap.getY()));
            return true;
        }

        return super.onTouchEvent(tap);
    }


    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        return false;
    }

    /**
     * onDoubleTap shows and hides the Button Bar at the Top of the View
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (mButtonBar.getVisibility() == View.GONE) {
            mButtonBar.setVisibility(View.VISIBLE);
        } else {
            mButtonBar.setVisibility(View.GONE);
        }
        return false;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public void onShowPress(MotionEvent tap) {
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

}
