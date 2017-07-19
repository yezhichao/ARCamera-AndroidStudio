package com.simoncherry.arcamera.activity;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.sensetime.stmobileapi.STMobileFaceAction;
import com.sensetime.stmobileapi.STUtils;
import com.simoncherry.arcamera.R;
import com.simoncherry.arcamera.codec.CameraRecorder;
import com.simoncherry.arcamera.custom.CircularProgressView;
import com.simoncherry.arcamera.filter.camera.AFilter;
import com.simoncherry.arcamera.filter.camera.FilterFactory;
import com.simoncherry.arcamera.filter.camera.LandmarkFilter;
import com.simoncherry.arcamera.gl.Camera1Renderer;
import com.simoncherry.arcamera.gl.CameraTrackRenderer;
import com.simoncherry.arcamera.gl.FrameCallback;
import com.simoncherry.arcamera.gl.My3DRenderer;
import com.simoncherry.arcamera.gl.MyRenderer;
import com.simoncherry.arcamera.gl.TextureController;
import com.simoncherry.arcamera.util.Accelerometer;
import com.simoncherry.arcamera.util.FileUtls;
import com.simoncherry.arcamera.util.PermissionUtils;

import org.rajawali3d.renderer.ISurfaceRenderer;
import org.rajawali3d.view.ISurface;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ARCamActivity extends AppCompatActivity implements FrameCallback {

    private final static String TAG = ARCamActivity.class.getSimpleName();
    // 拍照尺寸
    private final static int IMAGE_WIDTH = 720;
    private final static int IMAGE_HEIGHT = 1280;
    // 录像尺寸
    private final static int VIDEO_WIDTH = 384;
    private final static int VIDEO_HEIGHT = 640;
    // SenseTime人脸检测最大支持的尺寸为 640 x 480
    private final static int PREVIEW_WIDTH = 640;
    private final static int PREVIEW_HEIGHT = 480;

    private Context mContext;

    // 用于渲染相机预览画面
    private SurfaceView mSurfaceView;
    // 用于显示人脸检测参数
    private TextView mTrackText, mActionText;
    // 处理滤镜逻辑
    protected TextureController mController;
    private MyRenderer mRenderer;
    // 相机Id，后置是0，前置是1
    private int cameraId = 1;
    // 默认的相机滤镜的Id
    protected int mCurrentFilterId = R.id.menu_camera_default;
    // 加速度计工具类，似乎是用于人脸检测的
    private static Accelerometer mAccelerometer;

    // 由于不会写显示3D模型的滤镜，暂时借助于OpenGL ES引擎Rajawali
    // 用于渲染3D模型
    private ISurface mRenderSurface;
    private ISurfaceRenderer mISurfaceRenderer;
    // 因为渲染相机和3D模型的SurfaceView是分开的，拍照/录像时只能分别取两路数据，再合并
    // 拍照用的
    private Bitmap mRajawaliBitmap = null;
    // 录像用的
    private int[] mRajawaliPixels = null;

    // 拍照/录像按钮
    private CircularProgressView mCapture;
    // 处理录像逻辑
    private CameraRecorder mp4Recorder;
    private ExecutorService mExecutor;
    // 录像持续的时间
    private long time;
    // 录像最长时间限制
    private long maxTime = 20000;
    // 步长
    private long timeStep = 50;
    // 录像标志位
    private boolean recordFlag = false;
    // 处理帧数据的标志位 0为拍照 1为录像
    private int mFrameType = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = ARCamActivity.this;
        // 用于人脸检测
        mAccelerometer = new Accelerometer(this);
        mAccelerometer.start();

        PermissionUtils.askPermission(this, new String[]{Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 10, initViewRunnable);
    }

    protected void setContentView(){
        setContentView(R.layout.activity_ar_cam);
        initSurfaceView();
        initRajawaliSurface();
        initCaptureButton();
        initMenuButton();
        initCommonView();
    }

    private void initSurfaceView() {
        mSurfaceView = (SurfaceView) findViewById(R.id.camera_surface);
        // 按住屏幕取消滤镜，松手恢复滤镜，以便对比
        mSurfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        mController.clearFilter();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mController.addFilter(FilterFactory.getFilter(getResources(), mCurrentFilterId));
                        break;
                }
                return true;
            }
        });
    }

    private void initRajawaliSurface() {
        mRenderSurface = (org.rajawali3d.view.SurfaceView) findViewById(R.id.rajwali_surface);
        // 将Rajawali的SurfaceView的背景设为透明
        ((org.rajawali3d.view.SurfaceView) mRenderSurface).setTransparent(true);
        // 将Rajawali的SurfaceView的尺寸设为录像的尺寸
        ((org.rajawali3d.view.SurfaceView) mRenderSurface).getHolder().setFixedSize(VIDEO_WIDTH, VIDEO_HEIGHT);
        mISurfaceRenderer = new My3DRenderer(this);
        mRenderSurface.setSurfaceRenderer(mISurfaceRenderer);
        ((View) mRenderSurface).bringToFront();

        // 拍照时，先取Rajawali的帧数据，转成Bitmap待用；再取相机预览的帧数据，最后合成
        ((org.rajawali3d.view.SurfaceView) mRenderSurface).setOnTakeScreenshotListener(new org.rajawali3d.view.SurfaceView.OnTakeScreenshotListener() {
            @Override
            public void onTakeScreenshot(Bitmap bitmap) {
                Log.e(TAG, "onTakeScreenshot(Bitmap bitmap)");
                mRajawaliBitmap = bitmap;
                mController.takePhoto();
            }
        });
        // 录像时，取Rajawali的帧数据待用
        ((org.rajawali3d.view.SurfaceView) mRenderSurface).setOnTakeScreenshotListener2(new org.rajawali3d.view.SurfaceView.OnTakeScreenshotListener2() {
            @Override
            public void onTakeScreenshot(int[] pixels) {
                Log.e(TAG, "onTakeScreenshot(byte[] pixels)");
                mRajawaliPixels = pixels;
            }
        });
    }

    private void initCaptureButton() {
        mCapture = (CircularProgressView) findViewById(R.id.btn_capture);
        mCapture.setTotal((int)maxTime);
        // 拍照/录像按钮的逻辑
        mCapture.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()){
                    case MotionEvent.ACTION_DOWN:
                        recordFlag = false;
                        time = System.currentTimeMillis();
                        mCapture.postDelayed(captureTouchRunnable, 500);
                        break;
                    case MotionEvent.ACTION_UP:
                        recordFlag = false;
                        // 短按拍照
                        if(System.currentTimeMillis() - time < 500){
                            mFrameType = 0;
                            mCapture.removeCallbacks(captureTouchRunnable);
                            mController.setFrameCallback(IMAGE_WIDTH, IMAGE_HEIGHT, ARCamActivity.this);
                            // 拍照时，先取Rajawali的帧数据
                            ((org.rajawali3d.view.SurfaceView) mRenderSurface).takeScreenshot();
                        }
                        break;
                }
                return false;
            }
        });
    }

    private void initMenuButton() {
        ImageView ivFilter = (ImageView) findViewById(R.id.iv_filter);
        ImageView ivOrnament = (ImageView) findViewById(R.id.iv_ornament);
        ImageView ivEffect = (ImageView) findViewById(R.id.iv_effect);
        ImageView ivMask = (ImageView) findViewById(R.id.iv_mask);

        ivFilter.setColorFilter(Color.WHITE);
        ivOrnament.setColorFilter(Color.WHITE);
        ivEffect.setColorFilter(Color.WHITE);
        ivMask.setColorFilter(Color.WHITE);

        View.OnClickListener onClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switch (v.getId()) {
                    case R.id.iv_filter:
                        Toast.makeText(mContext, "click filter", Toast.LENGTH_SHORT).show();
                        break;
                    case R.id.iv_ornament:
                        Toast.makeText(mContext, "click ornament", Toast.LENGTH_SHORT).show();
                        break;
                    case R.id.iv_effect:
                        Toast.makeText(mContext, "click effect", Toast.LENGTH_SHORT).show();
                        break;
                    case R.id.iv_mask:
                        Toast.makeText(mContext, "click mask", Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        };

        ivFilter.setOnClickListener(onClickListener);
        ivOrnament.setOnClickListener(onClickListener);
        ivEffect.setOnClickListener(onClickListener);
        ivMask.setOnClickListener(onClickListener);
    }

    private void initCommonView() {
        mTrackText = (TextView) findViewById(R.id.tv_track);
        mActionText = (TextView) findViewById(R.id.tv_action);
    }

    // 初始化的Runnable
    private Runnable initViewRunnable = new Runnable() {
        @Override
        public void run() {
            mExecutor = Executors.newSingleThreadExecutor();
            mController = new TextureController(mContext);
            // 设置数据源
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mRenderer = new CameraTrackRenderer(mContext, (CameraManager)getSystemService(CAMERA_SERVICE), mController, cameraId);
                // 人脸检测的回调
                ((CameraTrackRenderer) mRenderer).setTrackCallBackListener(new CameraTrackRenderer.TrackCallBackListener() {
                    @Override
                    public void onTrackDetected(STMobileFaceAction[] faceActions, final int orientation, final int value,
                                                final float pitch, final float roll, final float yaw,
                                                final int eye_dist, final int id, final int eyeBlink, final int mouthAh,
                                                final int headYaw, final int headPitch, final int browJump) {
                        onTrackDetectedCallback(faceActions, orientation, value,
                                pitch, roll, yaw, eye_dist, id, eyeBlink, mouthAh, headYaw, headPitch, browJump);
                    }
                });

            }else{
                // Camera1暂时没写人脸检测
                mRenderer = new Camera1Renderer(mController, cameraId);
            }

            setContentView();

            mController.setFrameCallback(IMAGE_WIDTH, IMAGE_HEIGHT, ARCamActivity.this);
            mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    mController.surfaceCreated(holder);
                    mController.setRenderer(mRenderer);
                }
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    mController.surfaceChanged(width, height);
                }
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    mController.surfaceDestroyed();
                }
            });
        }
    };

    //录像的Runnable
    private Runnable captureTouchRunnable = new Runnable() {
        @Override
        public void run() {
            recordFlag = true;
            mExecutor.execute(recordRunnable);
        }
    };

    private Runnable recordRunnable = new Runnable() {

        @Override
        public void run() {
            mFrameType = 1;
            long timeCount = 0;
            if(mp4Recorder == null){
                mp4Recorder = new CameraRecorder();
            }
            long time = System.currentTimeMillis();
            String savePath = FileUtls.getPath(getApplicationContext(), "video/", time + ".mp4");
            mp4Recorder.setSavePath(FileUtls.getPath(getApplicationContext(), "video/", time+""), "mp4");
            try {
                mp4Recorder.prepare(VIDEO_WIDTH, VIDEO_HEIGHT);
                mp4Recorder.start();
                mController.setFrameCallback(VIDEO_WIDTH, VIDEO_HEIGHT, ARCamActivity.this);
                mController.startRecord();
                ((org.rajawali3d.view.SurfaceView) mRenderSurface).startRecord();

                while (timeCount <= maxTime && recordFlag){
                    long start = System.currentTimeMillis();
                    mCapture.setProcess((int)timeCount);
                    long end = System.currentTimeMillis();
                    try {
                        Thread.sleep(timeStep - (end - start));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    timeCount += timeStep;
                }
                mController.stopRecord();
                ((org.rajawali3d.view.SurfaceView) mRenderSurface).stopRecord();

                if(timeCount < 2000){
                    mp4Recorder.cancel();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mCapture.setProcess(0);
                            Toast.makeText(mContext, "录像时间太短了", Toast.LENGTH_SHORT).show();
                        }
                    });
                }else{
                    mp4Recorder.stop();
                    recordComplete(mFrameType, savePath);
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    };

    private void recordComplete(int type, final String path){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCapture.setProcess(0);
                Toast.makeText(mContext,"文件保存路径："+path,Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionUtils.onRequestPermissionsResult(requestCode == 10, grantResults, initViewRunnable,
                new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ARCamActivity.this, "没有获得必要的权限", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_camera_filter, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        mCurrentFilterId = item.getItemId();
        if (mCurrentFilterId == R.id.menu_camera_switch) {
            switchCamera();
        } else {
            setSingleFilter(mController, mCurrentFilterId);
        }
        return super.onOptionsItemSelected(item);
    }

    private void setSingleFilter(TextureController controller, int menuId) {
        controller.clearFilter();
        controller.addFilter(FilterFactory.getFilter(getResources(), menuId));
    }

    public void switchCamera(){
        cameraId = cameraId == 1 ? 0 : 1;
        if (mController != null) {
            mController.destroy();
        }
        initViewRunnable.run();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mController != null) {
            mController.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mController != null) {
            mController.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mController != null) {
            mController.destroy();
        }
    }

    @Override
    public void onFrame(final byte[] bytes, long time) {
        if (mp4Recorder != null && mFrameType == 1) {  // 处理录像
            handleVideoFrame(bytes);
        } else {  // 处理拍照
            handlePhotoFrame(bytes);
        }
    }

    private void handleVideoFrame(final byte[] bytes) {
        // 如果Rajawali渲染的3D模型帧数据不为空，就将两者合成
        if (mRajawaliPixels != null) {
            final ByteBuffer buf = ByteBuffer.allocate(mRajawaliPixels.length * 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            buf.asIntBuffer().put(mRajawaliPixels);
            mRajawaliPixels = null;
            byte[] tmpArray = buf.array();
            for (int i=0; i<bytes.length; i+=4) {
                byte a = tmpArray[i];
                byte r = tmpArray[i+1];
                byte g = tmpArray[i+2];
                byte b = tmpArray[i+3];
                // 取Rajawali不透明的部分
                // FIXME -- 贴图中透明和纯黑色部分的ARGB都是全0，导致纯黑色的地方被错误过滤。非技术上的解决方法是改贴图。。。
                if (a != 0) {
                    bytes[i] = a;
                    bytes[i + 1] = r;
                    bytes[i + 2] = g;
                    bytes[i + 3] = b;
                }
            }
        }
        mp4Recorder.feedData(bytes, time);
    }

    private void handlePhotoFrame(final byte[] bytes) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 将相机预览的帧数据转成Bitmap
                Bitmap bitmap = Bitmap.createBitmap(IMAGE_WIDTH,IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
                ByteBuffer b = ByteBuffer.wrap(bytes);
                bitmap.copyPixelsFromBuffer(b);
                // 如果Rajawali渲染的3D模型截图不为空，就将两者合成
                if (mRajawaliBitmap != null) {
                    Log.i(TAG, "mRajawaliBitmap != null");
                    mRajawaliBitmap = Bitmap.createScaledBitmap(mRajawaliBitmap, IMAGE_WIDTH, IMAGE_HEIGHT, false);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawBitmap(mRajawaliBitmap, 0, 0, null);
                    canvas.save(Canvas.ALL_SAVE_FLAG);
                    canvas.restore();
                    mRajawaliBitmap.recycle();
                    mRajawaliBitmap = null;
                } else {
                    Log.i(TAG, "mRajawaliBitmap == null");
                }
                // 最后保存
                saveBitmap(bitmap);
                bitmap.recycle();
                bitmap = null;
            }
        }).start();
    }

    // 保存拍照结果
    public void saveBitmap(Bitmap b){
        String path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/OpenGLDemo/photo/";
        File folder = new File(path);
        if(!folder.exists() && !folder.mkdirs()){
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ARCamActivity.this, "无法保存照片", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        long dataTake = System.currentTimeMillis();
        final String jpegName = path + dataTake + ".jpg";
        try {
            FileOutputStream fout = new FileOutputStream(jpegName);
            BufferedOutputStream bos = new BufferedOutputStream(fout);
            b.compress(Bitmap.CompressFormat.JPEG, 100, bos);
            bos.flush();
            bos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ARCamActivity.this, "保存成功->"+jpegName, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void onTrackDetectedCallback(STMobileFaceAction[] faceActions, final int orientation, final int value,
                                         final float pitch, final float roll, final float yaw,
                                         final int eye_dist, final int id, final int eyeBlink, final int mouthAh,
                                         final int headYaw, final int headPitch, final int browJump) {
        // 处理3D模型的旋转
        handle3dModelRotation(pitch, roll, yaw);
        // 处理3D模型的平移
        handle3dModelTransition(faceActions, orientation, eye_dist, yaw);
        // 处理需要用到人脸关键点的滤镜
        setLandmarkFilter(faceActions, orientation, mouthAh);
        // 显示人脸检测的参数
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTrackText.setText("TRACK: " + value + " MS"
                        + "\nPITCH: " + pitch + "\nROLL: " + roll + "\nYAW: " + yaw + "\nEYE_DIST:" + eye_dist);
                mActionText.setText("ID:" + id + "\nEYE_BLINK:" + eyeBlink + "\nMOUTH_AH:"
                        + mouthAh + "\nHEAD_YAW:" + headYaw + "\nHEAD_PITCH:" + headPitch + "\nBROW_JUMP:" + browJump);
            }
        });
    }

    // 处理需要用到人脸关键点的滤镜
    private void setLandmarkFilter(STMobileFaceAction[] faceActions, int orientation, int mouthAh) {
        AFilter aFilter = mController.getLastFilter();
        if(aFilter != null && aFilter instanceof LandmarkFilter && faceActions != null) {
            boolean rotate270 = orientation == 270;
            for (STMobileFaceAction r : faceActions) {
                Log.i("Test", "-->> face count = "+faceActions.length);
                PointF[] points = r.getFace().getPointsArray();
                float[] landmarkX = new float[points.length];
                float[] landmarkY = new float[points.length];
                for (int i = 0; i < points.length; i++) {
                    if (rotate270) {
                        points[i] = STUtils.RotateDeg270(points[i], PREVIEW_WIDTH, PREVIEW_HEIGHT);
                    } else {
                        points[i] = STUtils.RotateDeg90(points[i], PREVIEW_WIDTH, PREVIEW_HEIGHT);
                    }

                    landmarkX[i] = 1 - points[i].x / 480.0f;
                    landmarkY[i] = points[i].y / 640.0f;
                }
                ((LandmarkFilter) aFilter).setLandmarks(landmarkX, landmarkY);
                ((LandmarkFilter) aFilter).setMouthOpen(mouthAh);
            }
        }
    }

    // 处理3D模型的旋转
    private void handle3dModelRotation(final float pitch, final float roll, final float yaw) {
        ((My3DRenderer) mISurfaceRenderer).setAccelerometerValues(roll+90, -yaw, -pitch);
    }

    // 处理3D模型的平移
    private void handle3dModelTransition(STMobileFaceAction[] faceActions, int orientation, int eye_dist, float yaw) {
        boolean rotate270 = orientation == 270;
        STMobileFaceAction r = faceActions[0];
        Rect rect;
        if (rotate270) {
            rect = STUtils.RotateDeg270(r.getFace().getRect(), PREVIEW_WIDTH, PREVIEW_HEIGHT);
        } else {
            rect = STUtils.RotateDeg90(r.getFace().getRect(), PREVIEW_WIDTH, PREVIEW_HEIGHT);
        }

        float centerX = (rect.right + rect.left) / 2.0f;
        float centerY = (rect.bottom + rect.top) / 2.0f;
        float x = (centerX / PREVIEW_HEIGHT) * 2.0f - 1.0f;
        float y = (centerY / PREVIEW_WIDTH) * 2.0f - 1.0f;
        float tmp = eye_dist * 0.000001f - 1115;  // 1115xxxxxx ~ 1140xxxxxx - > 0 ~ 25
        tmp = (float) (tmp / Math.cos(Math.PI*yaw/180));  // 根据旋转角度还原两眼距离
        tmp = tmp * 0.04f;  // 0 ~ 25 -> 0 ~ 1
        float z = tmp * 3.0f + 1.0f;
        Log.e(TAG, "transition: x= " + x + ", y= " + y + ", z= " + z);

        My3DRenderer renderer = ((My3DRenderer) mISurfaceRenderer);
        renderer.getCurrentCamera().setX(x);
        renderer.getCurrentCamera().setY(y);
        renderer.setScale(z);
    }
}
