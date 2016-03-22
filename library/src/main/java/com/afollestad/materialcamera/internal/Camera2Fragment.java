package com.afollestad.materialcamera.internal;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import com.afollestad.materialcamera.R;
import com.afollestad.materialcamera.util.CameraUtil;
import com.afollestad.materialcamera.util.Degrees;
import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.afollestad.materialcamera.internal.BaseCaptureActivity.*;

/**
 * @author Aidan Follestad (afollestad)
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Fragment extends BaseCameraFragment implements View.OnClickListener {

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mPreviewSession;
    private AutoFitTextureView mTextureView;
    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;

    private Size mPreviewSize;
    private Size mVideoSize;
    @Degrees.DegreeUnits
    private int mDisplayOrientation;

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewBuilder;
    /**
     * {@link CaptureRequest} generated by {@link #mPreviewBuilder}
     */
    private CaptureRequest mPreviewRequest;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }
    };

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mTextureView) {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;

            String errorMsg = "Unknown camera error";
            switch (error) {
                case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                    errorMsg = "Camera is already in use.";
                    break;
                case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                    errorMsg = "Max number of cameras are open, close previous cameras first.";
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                    errorMsg = "Camera is disabled, e.g. due to device policies.";
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                    errorMsg = "Camera device has encountered a fatal error, please try again.";
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                    errorMsg = "Camera service has encountered a fatal error, please try again.";
                    break;
            }
            throwError(new Exception(errorMsg));
        }
    };

    //////////////////////// STILL CAPTURE ///////////////////////
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;



    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

    };


    //////////////////////// END OF STILL CAPTURE ///////////////////////

    public static Camera2Fragment newInstance() {
        Camera2Fragment fragment = new Camera2Fragment();
        fragment.setRetainInstance(true);
        return fragment;
    }

    private static Size chooseVideoSize(BaseCaptureInterface ci, Size[] choices) {
        Size backupSize = null;
        for (Size size : choices) {
            if (size.getHeight() <= ci.videoPreferredHeight()) {
                if (size.getWidth() == size.getHeight() * ci.videoPreferredAspect())
                    return size;
                if (ci.videoPreferredHeight() >= size.getHeight())
                    backupSize = size;
            }
        }
        if (backupSize != null) return backupSize;
        LOG(Camera2Fragment.class, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            LOG(Camera2Fragment.class, "Couldn't find any suitable preview size");
            return aspectRatio;
        }
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            mTextureView.getSurfaceTexture().release();
        } catch (Throwable ignored) {
        }
        mTextureView = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        stopBackgroundThread();
        super.onPause();
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        stopCounter();
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void openCamera() {
        final int width = mTextureView.getWidth();
        final int height = mTextureView.getHeight();

        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing()) return;

        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throwError(new Exception("Time out waiting to lock camera opening."));
                return;
            }

            if (mInterface.getFrontCamera() == null || mInterface.getBackCamera() == null) {
                for (String cameraId : manager.getCameraIdList()) {
                    if (cameraId == null) continue;
                    if (mInterface.getFrontCamera() != null && mInterface.getBackCamera() != null)
                        break;
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                    //noinspection ConstantConditions
                    int facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT)
                        mInterface.setFrontCamera(cameraId);
                    else if (facing == CameraCharacteristics.LENS_FACING_BACK)
                        mInterface.setBackCamera(cameraId);
                }
            }

            if (mInterface.getCurrentCameraPosition() == CAMERA_POSITION_UNKNOWN) {
                if (getArguments().getBoolean(CameraIntentKey.DEFAULT_TO_FRONT_FACING, false)) {
                    // Check front facing first
                    if (mInterface.getFrontCamera() != null) {
                        mButtonFacing.setImageResource(mInterface.iconRearCamera());
                        mInterface.setCameraPosition(CAMERA_POSITION_FRONT);
                    } else {
                        mButtonFacing.setImageResource(mInterface.iconFrontCamera());
                        if (mInterface.getBackCamera() != null)
                            mInterface.setCameraPosition(CAMERA_POSITION_BACK);
                        else mInterface.setCameraPosition(CAMERA_POSITION_UNKNOWN);
                    }
                } else {
                    // Check back facing first
                    if (mInterface.getBackCamera() != null) {
                        mButtonFacing.setImageResource(mInterface.iconFrontCamera());
                        mInterface.setCameraPosition(CAMERA_POSITION_BACK);
                    } else {
                        mButtonFacing.setImageResource(mInterface.iconRearCamera());
                        if (mInterface.getFrontCamera() != null)
                            mInterface.setCameraPosition(CAMERA_POSITION_FRONT);
                        else mInterface.setCameraPosition(CAMERA_POSITION_UNKNOWN);
                    }
                }
            }

            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics = manager.getCameraCharacteristics((String) mInterface.getCurrentCameraId());
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            mVideoSize = chooseVideoSize((BaseCaptureInterface) activity, map.getOutputSizes(MediaRecorder.class));
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height, mVideoSize);

            //noinspection ConstantConditions,ResourceType
            @Degrees.DegreeUnits
            final int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            @Degrees.DegreeUnits
            int deviceRotation = Degrees.getDisplayRotation(getActivity());
            mDisplayOrientation = Degrees.getDisplayOrientation(
                    sensorOrientation, deviceRotation, getCurrentCameraPosition() == CAMERA_POSITION_FRONT);
            Log.d("Camera2Fragment", String.format("Orientations: Sensor = %d˚, Device = %d˚, Display = %d˚",
                    sensorOrientation, deviceRotation, mDisplayOrientation));

            int orientation = VideoStreamView.getScreenOrientation(activity);
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                    orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }

            configureTransform(width, height);
            mMediaRecorder = new MediaRecorder();

            // For still image captures, we use the largest available size.
            Size largest = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());
            mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                    ImageFormat.JPEG, /*maxImages*/2);
            mImageReader.setOnImageAvailableListener(
                    new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(ImageReader reader) {
                            Log.d("takeStillshot", "on image available");
                        }
                    }, mBackgroundHandler);
            Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            mFlashSupported = flashAvailable == null ? false : flashAvailable;


            // noinspection ResourceType
            manager.openCamera((String) mInterface.getCurrentCameraId(), mStateCallback, null);
        } catch (CameraAccessException e) {
            throwError(new Exception("Cannot access the camera.", e));
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            new ErrorDialog().show(getFragmentManager(), "dialog");
        } catch (InterruptedException e) {
            throwError(new Exception("Interrupted while trying to lock camera opening.", e));
        }
    }

    @Override
    public void closeCamera() {
        try {
            if (mOutputUri != null) {
                final File outputFile = new File(Uri.parse(mOutputUri).getPath());
                if (outputFile.length() == 0)
                    outputFile.delete();
            }
            mCameraOpenCloseLock.acquire();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (InterruptedException e) {
            throwError(new Exception("Interrupted while trying to lock camera opening.", e));
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void startPreview() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize)
            return;
        try {
            if (!setUpMediaRecorder()) return;
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();

            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);

            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mPreviewSession = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    throwError(new Exception("Camera configuration failed"));
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            mPreviewRequest = mPreviewBuilder.build();
            mPreviewSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private boolean setUpMediaRecorder() {
        final Activity activity = getActivity();
        if (null == activity) return false;
        final BaseCaptureInterface captureInterface = (BaseCaptureInterface) activity;
        if (mMediaRecorder == null)
            mMediaRecorder = new MediaRecorder();

        boolean canUseAudio = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            canUseAudio = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        if (canUseAudio) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        } else {
            Toast.makeText(getActivity(), R.string.mcam_no_audio_access, Toast.LENGTH_LONG).show();
        }
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        final CamcorderProfile profile = CamcorderProfile.get(0, mInterface.qualityProfile());
        mMediaRecorder.setOutputFormat(profile.fileFormat);
        mMediaRecorder.setVideoFrameRate(mInterface.videoFrameRate(profile.videoFrameRate));
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncodingBitRate(mInterface.videoEncodingBitRate(profile.videoBitRate));
        mMediaRecorder.setVideoEncoder(profile.videoCodec);

        if (canUseAudio) {
            mMediaRecorder.setAudioEncodingBitRate(mInterface.audioEncodingBitRate(profile.audioBitRate));
            mMediaRecorder.setAudioChannels(profile.audioChannels);
            mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);
            mMediaRecorder.setAudioEncoder(profile.audioCodec);
        }

        Uri uri = Uri.fromFile(getOutputMediaFile());
        mOutputUri = uri.toString();
        mMediaRecorder.setOutputFile(uri.getPath());

        if (captureInterface.maxAllowedFileSize() > 0) {
            mMediaRecorder.setMaxFileSize(captureInterface.maxAllowedFileSize());
            mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                @Override
                public void onInfo(MediaRecorder mediaRecorder, int what, int extra) {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        Toast.makeText(getActivity(), R.string.mcam_file_size_limit_reached, Toast.LENGTH_SHORT).show();
                        stopRecordingVideo(false);
                    }
                }
            });
        }

        mMediaRecorder.setOrientationHint(mDisplayOrientation);

        try {
            mMediaRecorder.prepare();
            return true;
        } catch (Throwable e) {
            throwError(new Exception("Failed to prepare the media recorder: " + e.getMessage(), e));
            return false;
        }
    }

    @Override
    public boolean startRecordingVideo() {
        super.startRecordingVideo();
        try {
            // UI
            mButtonVideo.setImageResource(mInterface.iconStop());
            if (!CameraUtil.isArcWelder())
                mButtonFacing.setVisibility(View.GONE);

            // Only start counter if count down wasn't already started
            if (!mInterface.hasLengthLimit()) {
                mInterface.setRecordingStart(System.currentTimeMillis());
                startCounter();
            }

            // Start recording
            mMediaRecorder.start();

            mButtonVideo.setEnabled(false);
            mButtonVideo.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mButtonVideo.setEnabled(true);
                }
            }, 200);

            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            mInterface.setRecordingStart(-1);
            stopRecordingVideo(false);
            throwError(new Exception("Failed to start recording: " + t.getMessage(), t));
        }
        return false;
    }

    @Override
    public void stopRecordingVideo(boolean reachedZero) {
        super.stopRecordingVideo(reachedZero);

        if (mInterface.hasLengthLimit() && mInterface.shouldAutoSubmit() &&
                (mInterface.getRecordingStart() < 0 || mMediaRecorder == null)) {
            stopCounter();
            releaseRecorder();
            mInterface.onShowPreview(mOutputUri, reachedZero);
            return;
        }

        if (!mInterface.didRecord())
            mOutputUri = null;

        releaseRecorder();
        mButtonVideo.setImageResource(mInterface.iconRecord());
        if (!CameraUtil.isArcWelder())
            mButtonFacing.setVisibility(View.VISIBLE);
        if (mInterface.getRecordingStart() > -1 && getActivity() != null)
            mInterface.onShowPreview(mOutputUri, reachedZero);

        stopCounter();
    }

    ///////////////////////// STILL SHOT
    @Override
    public void takeStillshot() {
        super.takeStillshot();
        // http://pierrchen.blogspot.si/2015/01/android-camera2-api-explained.html
        // https://github.com/googlesamples/android-Camera2Basic/blob/master/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.java

        lockFocus();
    }

    private void lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;

            mPreviewSession.capture(mPreviewBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mPreviewSession.capture(mPreviewBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #takeStillshot()}.
     */
    private void captureStillPicture() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setFlashMode(captureBuilder);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    Log.d("stillshot", "onCaptureCompleted");
                    unlockFocus();
                }
            };

            mPreviewSession.stopRepeating();
            mPreviewSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setFlashMode(mPreviewBuilder);
            mPreviewSession.capture(mPreviewBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mPreviewSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setFlashMode(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            int aeMode;
            switch (mInterface.getFlashMode()) {
                case FLASH_MODE_AUTO:
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
                    break;
                case FLASH_MODE_ALWAYS_ON:
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
                    break;
                case FLASH_MODE_OFF:
                default:
                    aeMode = CaptureRequest.CONTROL_AE_MODE_OFF;
                    break;
            }
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
        }
    }

    //////////////////////// END OF STILL SHOT
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    public static class ErrorDialog extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new MaterialDialog.Builder(activity)
                    .content("This device doesn't support the Camera2 API.")
                    .positiveText(android.R.string.ok)
                    .onAny(new MaterialDialog.SingleButtonCallback() {
                        @Override
                        public void onClick(@NonNull MaterialDialog materialDialog, @NonNull DialogAction dialogAction) {
                            activity.finish();
                        }
                    }).build();
        }
    }
}