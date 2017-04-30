package org.wallpanelproject.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.opengl.GLES11Ext;
import android.os.Handler;
import android.util.Log;

import com.jjoe64.motiondetection.motiondetection.AggregateLumaMotionDetection;
import com.jjoe64.motiondetection.motiondetection.ImageProcessing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public class CameraReader {
    private final String TAG = WallPanelService.class.getName();

    private WallPanelService wallPanel;
    private SurfaceTexture mSurfaceTexture;
    private int cameraId = 0;
    private Camera mCamera;

    private int mPreviewFormat = 0;
    private int mPreviewWidth = 0;
    private int mPreviewHeight = 0;
    private Rect mPreviewRect = null;
    private final int mJpegQuality = 80;

    private byte[] currentFrame = new byte[0];

    private AggregateLumaMotionDetection motionDetector;
    private CameraDetectorCallback cameraDetectorCallback;
    private int minLuma = 1000;

    private Handler detectorCheckHandler;
    private boolean checkFaces = false;
    private long mCheckInterval = 1000;

    CameraReader(WallPanelService wallPanelService) {
        wallPanel = wallPanelService;
        mSurfaceTexture = new SurfaceTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
    }

    protected void finalize() {
        try { super.finalize(); } catch (Throwable t) { t.printStackTrace(); }
        stop();
    }

    public void start(int cameraId, long checkInterval, CameraDetectorCallback cameraDetectorCallback) {
        Log.d(TAG, "start Called");
        mCheckInterval = checkInterval;
        this.cameraDetectorCallback = cameraDetectorCallback;
        if (mCamera == null) {
            this.cameraId = cameraId;
            mCamera = getCameraInstance(cameraId);
            if (mCamera == null) {
                Log.e(TAG, "There is no camera so nothing is going to happen :(");
            } else {
                try {
                    mCamera.setPreviewTexture(mSurfaceTexture);
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
                final Camera.Parameters params = mCamera.getParameters();
                mPreviewFormat = mCamera.getParameters().getPreviewFormat();
                final Camera.Size previewSize = params.getPreviewSize();
                mPreviewWidth = previewSize.width;
                mPreviewHeight = previewSize.height;
                mPreviewRect = new Rect(0,0,mPreviewWidth,mPreviewHeight);
                final int BITS_PER_BYTE = 8;
                final int bytesPerPixel = ImageFormat.getBitsPerPixel(mPreviewFormat) / BITS_PER_BYTE;

                final int mPreviewBufferSize = mPreviewWidth * mPreviewHeight * bytesPerPixel * 3 / 2 + 1;
                byte[] mBuffer = new byte[mPreviewBufferSize];
                currentFrame = new byte[mPreviewBufferSize];
                mCamera.addCallbackBuffer(mBuffer);
                mCamera.setPreviewCallbackWithBuffer(previewCallback);

                mCamera.startPreview();
            }
        }

        if (detectorCheckHandler == null) {
            detectorCheckHandler = new Handler();
            detectorCheckHandler.postDelayed(checkDetections, mCheckInterval);
        }
    }

    public void startMotionDetection(int minLuma, int leniency) {
        Log.d(TAG, "startMotionDetection Called");
        if (motionDetector == null) {
            motionDetector = new AggregateLumaMotionDetection();
            this.minLuma = minLuma;
            motionDetector.setLeniency(leniency);
        }
    }

    public void startFaceDetection() {
        Log.d(TAG, "startFaceDetection Called");
        checkFaces = true;
        mCamera.startFaceDetection();
        mCamera.setFaceDetectionListener(faceDetectionListener);
    }

    public void stop() {
        Log.d(TAG, "stop Called");

        if (detectorCheckHandler != null) {
            detectorCheckHandler.removeCallbacks(checkDetections);
            detectorCheckHandler = null;
        }

        if (motionDetector != null) {
            motionDetector = null;
        }

        checkFaces = false;

        if (mCamera != null) {
            mCamera.setFaceDetectionListener(null);
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    private Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            byte[] lastFrame = currentFrame;
            currentFrame = data;
            cam.addCallbackBuffer(lastFrame);
        }
    };

    private Camera getCameraInstance(int cameraId) {
        Log.d(TAG, "getCameraInstance called");
        Camera c = null;
        int numCameras = Camera.getNumberOfCameras();
        try {
            if (cameraId >= numCameras)
                c = Camera.open(0);
            else
                c = Camera.open(cameraId);
        }
        catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
        return c;
    }

    public static ArrayList<String> getCameras() { // todo better camera descriptions
        ArrayList<String> result = new ArrayList<>();
        for (int i=0; i<Camera.getNumberOfCameras(); i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            result.add(java.text.MessageFormat.format(
                    "id:{0} facing:{1} orientation:{2}",
                    i, info.facing, info.orientation));
        }
        return result;
    }

    public byte[] getJpeg() {
        ByteArrayOutputStream outstr = new ByteArrayOutputStream();
        if (mCamera != null) {
            final YuvImage image = new YuvImage(currentFrame,
                    mPreviewFormat, mPreviewWidth, mPreviewHeight, null);
            image.compressToJpeg(mPreviewRect, mJpegQuality, outstr);
        } else {
            getCameraNotEnabledBitmap().compress(Bitmap.CompressFormat.JPEG, 100, outstr);
        }
        return outstr.toByteArray();
    }

    public Bitmap getBitmap() {
        if (mCamera != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            YuvImage yuvImage = new YuvImage(currentFrame, mPreviewFormat, mPreviewWidth, mPreviewHeight, null);
            yuvImage.compressToJpeg(new Rect(0, 0, mPreviewWidth, mPreviewHeight), 50, out);
            byte[] imageBytes = out.toByteArray();
            BitmapFactory.Options bitmap_options = new BitmapFactory.Options();
            bitmap_options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bitmap_options);
        } else {
            return getCameraNotEnabledBitmap();
        }
    }

    private Bitmap getCameraNotEnabledBitmap() {
        Bitmap b = Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.FILL);
        c.drawPaint(paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(20);
        Rect r = new Rect();
        String text = "Camera Not Enabled ";
        c.getClipBounds(r);
        int cHeight = r.height();
        int cWidth = r.width();
        paint.setTextAlign(Paint.Align.LEFT);
        paint.getTextBounds(text, 0, text.length(), r);
        float x = cWidth / 2f - r.width() / 2f - r.left;
        float y = cHeight / 2f + r.height() / 2f - r.bottom;
        c.drawText(text, x, y, paint);

        return b;
    }

    private final Runnable checkDetections = new Runnable() {
        @Override
        public void run () {
            if (currentFrame.length > 0) {
                checkMotionDetection(currentFrame);
                //checkFaceDetection(getBitmap());
                //TODO add QR codes :)
            }
            detectorCheckHandler.postDelayed(this, mCheckInterval);
        }
    };

    private void checkMotionDetection(byte[] currentFrame) {
        if (motionDetector != null) {
            int[] img = ImageProcessing.decodeYUV420SPtoLuma(currentFrame, mPreviewWidth, mPreviewHeight);

            // check if it is too dark
            int lumaSum = 0;
            for (int i : img) {
                lumaSum += i;
            }
            if (lumaSum < minLuma) {
                if (cameraDetectorCallback != null) {
                    cameraDetectorCallback.onTooDark();
                }
            } else if (motionDetector.detect(img, mPreviewWidth, mPreviewHeight)) {
                // we have motion!
                if (cameraDetectorCallback != null) {
                    cameraDetectorCallback.onMotionDetected();
                }
            }
        }
    }

    private Camera.FaceDetectionListener faceDetectionListener = new Camera.FaceDetectionListener() {
        @Override
        public void onFaceDetection(Camera.Face[] faces, Camera camera) {
            if (cameraDetectorCallback != null) {
                cameraDetectorCallback.onFaceDetected();
            }
        }
    };

    /*private void checkFaceDetection(Bitmap img) {
        if (checkFaces) {
            FaceDetector face_detector = new FaceDetector(img.getWidth(), img.getHeight(), 1);
            FaceDetector.Face[] faces = new FaceDetector.Face[1];
            int face_count = face_detector.findFaces(img, faces);
            if (face_count > 0) {
                if (cameraDetectorCallback != null) {
                    cameraDetectorCallback.onFaceDetected();
                }
            }
        }
    }*/
}
