package org.wallpanelproject.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.opengl.GLES11Ext;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.face.FaceDetector;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.jjoe64.motiondetection.motiondetection.AggregateLumaMotionDetection;
import com.jjoe64.motiondetection.motiondetection.ImageProcessing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static android.graphics.ImageFormat.NV21;
import static android.graphics.ImageFormat.YV12;

public class CameraReader {
    private final String TAG = WallPanelService.class.getName();

    private final SurfaceTexture mSurfaceTexture;
    private Camera mCamera;
    private static ArrayList<String> cameraList;

    private int mPreviewFormat = 0;

    private byte[] currentFrame = new byte[0];
    private int currentWidth = 0;
    private int currentHeight = 0;

    private AggregateLumaMotionDetection motionDetector;
    private FaceDetector faceDetector;
    private CameraDetectorCallback cameraDetectorCallback;
    private int minLuma = 1000;

    private Handler detectorCheckHandler;
    private boolean checkQR = false;
    private boolean checkFace = false;
    private long mCheckInterval = 1000;

    private Context mContext;

    CameraReader(Context context) {
        mContext = context;
        mSurfaceTexture = new SurfaceTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        getCameras();
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
                currentWidth = previewSize.width;
                currentHeight = previewSize.height;
                final int BITS_PER_BYTE = 8;
                final int bytesPerPixel = ImageFormat.getBitsPerPixel(mPreviewFormat) / BITS_PER_BYTE;

                final int mPreviewBufferSize = currentWidth * currentHeight * bytesPerPixel * 3 / 2 + 1;
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
        if (faceDetector == null) {
            faceDetector = new FaceDetector.Builder(mContext)
                    .setProminentFaceOnly(true)
                    .build();
        }
        checkFace = true;
    }

    public void startQRCodeDetection() {
        Log.d(TAG, "startQRCodeDetection Called");
        checkQR = true;
    }

    public void stop() {
        Log.d(TAG, "stop Called");

        if (detectorCheckHandler != null) {
            detectorCheckHandler.removeCallbacks(checkDetections);
            detectorCheckHandler = null;
        }

        if (faceDetector != null) {
            faceDetector.release();
            faceDetector = null;
        }

        if (motionDetector != null) {
            motionDetector = null;
        }

        checkFace = false;
        checkQR = false;

        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    private final Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            byte[] lastFrame = currentFrame;
            Camera.Size s = cam.getParameters().getPreviewSize();
            currentWidth = s.width;
            currentHeight = s.height;
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
            c.setDisplayOrientation(180);
        }
        catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
        return c;
    }

    public static ArrayList<String> getCameras() {
        if (cameraList == null) {
            cameraList = new ArrayList<>();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                String description;
                try {
                    final Camera c = Camera.open(i);
                    Camera.Parameters p = c.getParameters();
                    final Camera.Size previewSize = p.getPreviewSize();
                    int width = previewSize.width;
                    int height = previewSize.height;
                    Camera.CameraInfo info = new Camera.CameraInfo();
                    Camera.getCameraInfo(i, info);
                    description = java.text.MessageFormat.format(
                            "{0}: {1} Camera {3}x{4} {2}º",
                            i,
                            (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) ? "Front" : "Back",
                            info.orientation,
                            width,
                            height);
                    c.release();
                } catch (Exception e) {
                    Log.e("CameraReader", "Had a problem reading camera " + i);
                    e.printStackTrace();
                    description = java.text.MessageFormat.format("{0}: Error", i);
                }
                cameraList.add(description);
            }
        }
        return cameraList;
    }

    public byte[] getJpeg() { //todo: optimize
        ByteArrayOutputStream outstr = new ByteArrayOutputStream();
        //if (mCamera != null) {
        //    final YuvImage image = new YuvImage(currentFrame,
        //            mPreviewFormat, currentWidth, currentHeight, null);
        //    int mJpegQuality = 80;
        //    image.compressToJpeg(new Rect(0,0,currentWidth, currentHeight), mJpegQuality, outstr);
        //} else {
        //    getCameraNotEnabledBitmap().compress(Bitmap.CompressFormat.JPEG, 100, outstr);
        //}
        getBitmap().compress(Bitmap.CompressFormat.JPEG, 80, outstr);
        return outstr.toByteArray();
    }

    private Bitmap getBitmap(byte[] data) {
        if (mCamera != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            YuvImage yuvImage = new YuvImage(data, mPreviewFormat, currentWidth, currentHeight, null);
            yuvImage.compressToJpeg(new Rect(0, 0, currentWidth, currentHeight), 100, out);
            byte[] imageBytes = out.toByteArray();
            BitmapFactory.Options bitmap_options = new BitmapFactory.Options();
            bitmap_options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap result =  BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bitmap_options);

            WindowManager windowService = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            final int currentRotation = windowService.getDefaultDisplay().getRotation();
            int rotate = 270; //todo make this match the selected camera
            if (currentRotation == Surface.ROTATION_90) rotate += 90;
            else if (currentRotation == Surface.ROTATION_180) rotate += 180;
            else if (currentRotation == Surface.ROTATION_270) rotate += 270;
            rotate = rotate % 360;

            Matrix matrix = new Matrix();
            matrix.postRotate(rotate);
            return Bitmap.createBitmap(result, 0, 0, currentWidth, currentHeight, matrix, true);
        } else {
            return getCameraNotEnabledBitmap();
        }
    }

    public Bitmap getBitmap() {
        return getBitmap(currentFrame);
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
                checkFacePresent(currentFrame);
                checkQRCode(currentFrame);
            }
            detectorCheckHandler.postDelayed(this, mCheckInterval);
        }
    };

    private void checkMotionDetection(byte[] currentFrame) {
        if (motionDetector != null) {
            int[] img = ImageProcessing.decodeYUV420SPtoLuma(currentFrame, currentWidth, currentHeight);

            // check if it is too dark
            int lumaSum = 0;
            for (int i : img) {
                lumaSum += i;
            }
            if (lumaSum < minLuma) {
                if (cameraDetectorCallback != null) {
                    cameraDetectorCallback.onTooDark();
                }
            } else if (motionDetector.detect(img, currentWidth, currentHeight)) {
                // we have motion!
                if (cameraDetectorCallback != null) {
                    cameraDetectorCallback.onMotionDetected();
                }
            }
        }
    }

    private void checkFacePresent(byte[] currentFrame) {
        if (checkFace) {
            if (faceDetector.isOperational()) { //todo: rebuild the face detector when the screen rotates
//                WindowManager windowService = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
//                final int currentRotation = windowService.getDefaultDisplay().getRotation();
                Frame frame = new Frame.Builder()
                        .setBitmap(getBitmap())
//                        .setRotation(currentRotation)
                        .build();
                if (faceDetector.detect(frame).size() > 0) {
                    if (cameraDetectorCallback != null) {
                        cameraDetectorCallback.onFaceDetected();
                    }
                }
            }
        }
    }

    private void checkQRCode(byte[] currentFrame) { //todo: move QR code to vision library!
        if (checkQR) {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(currentFrame,
                    currentWidth, currentHeight, 0, 0, currentWidth, currentHeight, false);
            BinaryBitmap bBitmap = new BinaryBitmap(new HybridBinarizer(source));
            MultiFormatReader reader = new MultiFormatReader();
            try {
                Result result = reader.decode(bBitmap);
                String data = result.getText();
                Log.i(TAG, "QR Code result: " + data);
                if (cameraDetectorCallback != null) {
                    cameraDetectorCallback.onQRCode(data);
                }
            } catch (NotFoundException ex) {
                // no QR code!
            }
        }
    }
}
