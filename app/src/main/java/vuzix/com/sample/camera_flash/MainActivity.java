/***************************************************************************
Copyright (c) 2018, Vuzix Corporation
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

*  Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

*  Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

*  Neither the name of Vuzix Corporation nor the names of
   its contributors may be used to endorse or promote products derived
   from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ***************************************************************************/
package vuzix.com.sample.camera_flash;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
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
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sample activity to demonstrate modifying the camera flash settings for applications that want to
 * directly capture photographs with the flash
 */
public class MainActivity extends Activity implements RotationListener.rotationCallbackFn {
    private static final String TAG = "CameraFlash_App";
    private static final long PREVIEW_TIME_MILLISECS = 2000;
    private Button mTakingButton;
    private TextureView mTextureView;
    private String mCameraId;
    private CameraDevice mCameraDevice;
    private CameraManager mCameraManager;
    private CameraCaptureSession mCameraPreviewSessions;
    private CameraCaptureSession mCameraCaptureSessions;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private ImageReader mImageReader;

    private int mFlashMode;
    private boolean mTakingPicture;
    private boolean mSuspending;

    private RotationListener mRotationListener;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundCaptureHandler;
    private HandlerThread mBackgroundCaptureThread;
    private Handler mHandler;

    private final static int TAKEPICTURE_COMPLETED      = 1001;
    private static final int REQUEST_CAMERA_PERMISSION  = 200;

    private static final int FLASH_OFF                  = 2001;
    private static final int FLASH_ON                   = 2002;
    private static final int FLASH_TORCH                = 2003;

    private final int[] RotationConversion = {  /* ROTATION_0 = 0 */      0,
                                                /* ROTATION_90 = 1; */   90,
                                                /* ROTATION_180 = 2; */ 180,
                                                /* ROTATION_270 = 3; */ 270 };

    /**
     * Setup the view including camera preview, implements UI code for
     * @param savedInstanceState - just for the superclass. We ignore this
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Handle taking the picture. Disable the button while it processes.
        mTakingButton = (Button) findViewById(R.id.btn_takepicture);
        mTakingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClickTakePicture();
            }
        });

        // Set up the preview
        mTextureView = (TextureView) findViewById(R.id.texture);
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openCamera();
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        // Create a handler to respond when the photo is complete.
        mHandler = new Handler(Looper.getMainLooper()){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case TAKEPICTURE_COMPLETED:
                        onPictureComplete();
                        break;
                    default:
                        super.handleMessage(msg);
                        break;
                }
            }
        };

        // Create the listener to handle M400 orientation changes
        mRotationListener = new RotationListener();
    }

    /**
     * Called every time the user presses the "Take Picture" button. Maintains state of UI.
     */
    private void onClickTakePicture() {
        if(!mTakingPicture) {
            mTakingPicture = true;
            takeStillPicture();
            mTakingButton.setEnabled(false);
        }
    }

    /**
     * Called when processing the picture request completes
     */
    private void onPictureComplete() {
        createCameraPreview();
        try {
            Thread.sleep(PREVIEW_TIME_MILLISECS); // Sleep for 3 seconds to allow the user to see the photograph
        } catch (InterruptedException e) {
            // Just discard the exception.  We don't care if we were aborted
        }
        mTakingButton.setEnabled(true);
        mTakingPicture = false;
    }

    /**
     * Utility to start the background threads
     */
    protected synchronized  void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

        mBackgroundCaptureThread = new HandlerThread("Camera Capture");
        mBackgroundCaptureThread.start();
        mBackgroundCaptureHandler = new Handler(mBackgroundCaptureThread.getLooper());
    }

    /**
     * Utility to stop the background threads
     */
    protected synchronized void stopBackgroundThread() {
        mBackgroundCaptureThread.quitSafely();
        mBackgroundThread.quitSafely();

        try {
            mBackgroundCaptureThread.join();
            mBackgroundCaptureThread = null;
            mBackgroundCaptureHandler = null;

            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Take the photograph
     */
    protected void takeStillPicture() {
        if (null == mCameraDevice) {
            Log.e(TAG, "mCameraDevice is null");
            return;
        }

        switch (mFlashMode) {
            case FLASH_ON:
                // This does some extra work, then falls through to createCameraStillCapture()
                precaptureTrigger();
                break;
            case FLASH_OFF:
            case FLASH_TORCH:
                Log.i(TAG, "Capture called from takeStillPicture");
                createCameraStillCapture();
                break;
        }
    }


    /**
     * Creates the full path to the image file. Includes timestamp and dimensions
     *
     * @param width - int Width of image in pixels
     * @param height - int Height of image in pixels
     * @return
     */
    public String getOutputImagePath(int width, int height){
        File mediaStorageDir = new File(Environment.getExternalStorageDirectory(), "Pictures");
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                Log.e(TAG, "can not create the directory " + mediaStorageDir.getPath());
            }
        }
        String timeStamp = String.valueOf(System.currentTimeMillis());
        return (mediaStorageDir.getPath() + File.separator + "IMAGE_" + timeStamp + "_" + width + "x" + height + ".jpg");
    }


    /**
     * Utility to get the screen rotation in degrees
     * @return int angle. For an Activity properly in sensorLandscape, this is either 0 or 180.
     */
    private int getScreenRotationDegrees() {
        // Get one of the 4 integer indexes of a system rotation
        final int systemRotation = ((WindowManager)getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        // Convert to corresponding degrees
        return RotationConversion[systemRotation];
    }

    /**
     * Utility to determine the correct image rotation
     * @return int degree value from 0-359 to rotate the captured image to the right to view it normally
     */
    private int rotateImageForOrientation() {
        int sensorOrientation = 0;
        boolean isFrontFacing = false;
        try {
            CameraCharacteristics cameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            isFrontFacing = (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT);
        } catch (CameraAccessException e) {
            Log.d(TAG, "Did not get orientation. ", e);
            return 0;
        }
        int degrees = getScreenRotationDegrees();
        if(isFrontFacing) {
            degrees = -degrees;
        }
        degrees = (sensorOrientation - degrees + 360) % 360;
        Log.d(TAG, "Sensor front facing: " + isFrontFacing + " orientation: " + sensorOrientation + " jpeg orientation: " + degrees);
        return degrees;
    }

    /**
     * This method is called every time the device changes rotation. Update the preview
     *
     * @param newRotation int Either Surface.ROTATION_0 or Surface.ROTATION_180
     */
    @Override
    public void onRotationChanged(int newRotation) {
        //Log.d(TAG, "Rotation changed to " + Integer.toString(newRotation));
        updatePreview();
    }

    /**
     * Create the camera preview
     */
    protected synchronized void createCameraPreview() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            if ( (null == texture) || (null==mCameraDevice) ){
                return;
            }
            texture.setDefaultBufferSize(1920, 1080);// preview size
            Surface surface = new Surface(texture);

            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(surface);

            mCameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (null == mCameraDevice) {
                        return;
                    }
                    mCameraPreviewSessions = session;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * This activates the camera.  It includes permissions checks. If successful it then opens the preview.
     */
    private synchronized void openCamera() {
        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.i(TAG, "is camera open");
        try {
            // The M400 only has one camera, so the index can be constant
            mCameraId = mCameraManager.getCameraIdList()[0];
            // Add permission for camera, let user grant the permission.
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            // Open the camera and provide the required callbacks
            mCameraManager.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    Log.i(TAG, "onOpened");
                    mCameraDevice = camera;
                    mFlashMode = FLASH_OFF;
                    createCameraPreview();   // Only open the preview if the camera is opened successfully
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    mCameraDevice.close();
                }
                @Override
                public void onError(CameraDevice camera, int error) {
                    if(null != mCameraDevice){
                        mCameraDevice.close();
                        mCameraDevice = null;
                    }
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            mCameraManager = null;
            mCameraDevice = null;
        }catch(SecurityException e){
            e.printStackTrace();
        }
    }

    /**
     * Utility to set the flash modes and start the preview
     */
    protected void updatePreview() {
        if(null == mCameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        try {
            setPreviewFlashMode();
            int rotation = -getScreenRotationDegrees();
            mTextureView.setRotation(rotation);
            Log.d(TAG, "Screen rotated. Setting preview rotation to: " + rotation);
            mCameraPreviewSessions.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundCaptureHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Utility to set the flash modes and capture the image.
     *
     * This sets the flash mode, the image orientation, and adds the listener so we know when it is done
     */
    private void capture(){
        try {
            setCaptureFlashMode();
            // Properly account for the sensor rotation
            int degrees = rotateImageForOrientation();
            mCaptureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, degrees);
            mCameraCaptureSessions.capture(mCaptureRequestBuilder.build(), null, mBackgroundCaptureHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    /**
     * Sets up the camera device with all the required call-backs, and initiates the capture
     */
    private void createCameraStillCapture(){
        try {
            final int imageWidth =1920;        // image size
            final int imageHeight =1080;       // image size

            List<Surface> outputSurfaces = new ArrayList<Surface>();
            // Add an output surface to view the result of the photograph in place of the preview
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            texture.setDefaultBufferSize(1920, 1080); // preview size
            Surface surface = new Surface(texture);
            outputSurfaces.add(surface);

            // Add an output surface to write the image to a file
            ImageReader reader = ImageReader.newInstance(imageWidth, imageHeight, ImageFormat.JPEG, 1);
            outputSurfaces.add(reader.getSurface());

            mCaptureRequestBuilder =  mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            mCaptureRequestBuilder.addTarget(surface);
            mCaptureRequestBuilder.addTarget(reader.getSurface());

            // Create a listener that saves the image file and notifies us when complete
            final File file = new File(getOutputImagePath(imageWidth,imageHeight));
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                        Log.d(TAG, "Wrote: " + file.getPath());
                        completed();  // Send ourselves a message that we're completed
                    }
                }


                private void save(byte[] bytes) throws IOException {
                    OutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } finally {
                        if (null != output) {
                            output.close();
                        }
                    }
                }

                private void completed(){
                    Message msg = mHandler.obtainMessage();
                    msg.what = TAKEPICTURE_COMPLETED;
                    mHandler.sendMessage(msg);
                }

            }, mBackgroundHandler);

            // Begin the capture session, and take the photograph
            mCameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    mCameraCaptureSessions = session;
                    capture();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, mBackgroundCaptureHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * This sets up the Automatic Exposure to expect the flash. If the flash will be used for the
     * photograph, this configures a pre-trigger to allow the AE algorithms to analyze the light levels
     */
    private void precaptureTrigger(){
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        texture.setDefaultBufferSize(1920, 1080); // preview size
        Surface surface = new Surface(texture);

        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
            mCaptureRequestBuilder.addTarget(surface);

            mCameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                        session.capture(mCaptureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback(){
                            @Override
                            public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {

                            }
                            @Override
                            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {

                                   if(result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_INACTIVE
                                           || result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                                           || result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_CONVERGED
                                           || result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_LOCKED){
                                       Log.i(TAG, "Capture called from precaptureTrigger. AE_STATE: " + Integer.toString(result.get(CaptureResult.CONTROL_AE_STATE)));
                                   }
                            }
                        },mBackgroundHandler);

                        createCameraStillCapture();

                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }

                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Utility to translate our desired flash mode to the required API for the capture
     */
    private void setCaptureFlashMode(){

            switch(mFlashMode) {
                case FLASH_OFF:
                    mCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                    mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                    break;
                case FLASH_TORCH:
                    mCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                    break;
            }
    }

    /**
     * Utility to translate our desired flash mode to the required API for the preview
     */
    private void setPreviewFlashMode(){

        switch(mFlashMode){
            case FLASH_OFF:
                mCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                break;
            case FLASH_TORCH:
                mCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                break;
            case FLASH_ON:
                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                break;
        }
    }

    /**
     * Utility function to close the camera
     */
    private synchronized void closeCamera() {
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
        mCameraManager = null;
    }

    /**
     * When we resume, start the worker thread.  Re-open the camera if we previously closed it.
     */
    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");
        mRotationListener.listen(this, this);
        startBackgroundThread();
        if(mSuspending)
            openCamera();
        mSuspending = false;
    }

    /**
     * When we pause, close the camera, stop the worker thread, and remember we've done so
     */
    @Override
    protected void onPause() {
        Log.i(TAG, "onPause");
        closeCamera();
        stopBackgroundThread();
        mRotationListener.stop();
        mSuspending = true;
        super.onPause();
    }

    /**
     * Required interface for any activity that requests a run-time permission
     *
     * @see <a href="https://developer.android.com/training/permissions/requesting.html">https://developer.android.com/training/permissions/requesting.html</a>
     * @param requestCode int: The request code passed in requestPermissions(android.app.Activity, String[], int)
     * @param permissions String: The requested permissions. Never null.
     * @param grantResults int: The grant results for the corresponding permissions which is either PERMISSION_GRANTED or PERMISSION_DENIED. Never null.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            // Since we only request one "dangerous" permission, we don't need to worry about larger array sizes
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(MainActivity.this, "Sorry!, you don't have permission to run this app", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /**
     * Handler called upon the user selecting a flash mode using the options menu
     *
     * This simply stores the selected mode in a member variable
     *
     * @param item MenuItem: The menu item that was selected.
     * @return true if the item was consumed
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()){
            case R.id.flashoff:
                mFlashMode = FLASH_OFF;
                break;
            case R.id.flashon:
                mFlashMode = FLASH_ON;
                break;
            case R.id.flashtorch:
                mFlashMode = FLASH_TORCH;
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        createCameraPreview();
        return true;
    }

    /**
     * Create an options menu
     *
     * @param menu Menu: The options menu in which you place your items.
     * @return true so the menu is shown
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }
}
