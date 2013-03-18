package com.googlecode.javacv.facepreview.views;

import static com.googlecode.javacv.cpp.opencv_core.IPL_DEPTH_8U;
import static com.googlecode.javacv.cpp.opencv_core.cvClearMemStorage;
import static com.googlecode.javacv.cpp.opencv_core.cvGetSeqElem;
import static com.googlecode.javacv.cpp.opencv_core.cvLoad;
import static com.googlecode.javacv.cpp.opencv_highgui.cvSaveImage;
import static com.googlecode.javacv.cpp.opencv_objdetect.CV_HAAR_FIND_BIGGEST_OBJECT;
import static com.googlecode.javacv.cpp.opencv_objdetect.cvHaarDetectObjects;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.Camera;
import android.view.View;

import com.googlecode.javacpp.Loader;
import com.googlecode.javacv.cpp.opencv_core.CvMemStorage;
import com.googlecode.javacv.cpp.opencv_core.CvRect;
import com.googlecode.javacv.cpp.opencv_core.CvSeq;
import com.googlecode.javacv.cpp.opencv_core.IplImage;
import com.googlecode.javacv.cpp.opencv_objdetect;
import com.googlecode.javacv.cpp.opencv_objdetect.CvHaarClassifierCascade;
import com.googlecode.javacv.cpp.opencv_video.BackgroundSubtractorMOG2;
import com.googlecode.javacv.facepreview.compute.BackgroundConsistencyAnalysis;

// can we use startFaceDetection on camera? probably not
public class FaceViewWithAnalysis extends View implements Camera.PreviewCallback {
    public static final int CONSISTENCY_SUBSAMPLING_FACTOR = 8;
    public static final int RECOGNITION_SUBSAMPLING_FACTOR = 4;

    public IplImage grayImage;
    public IplImage largerGrayImage;
    private IplImage foreground;
    private Bitmap forgroundBitmap;
    public String displayedText = "Tap the screen to set your face - This side up.";    
    
    private CvHaarClassifierCascade classifier;
    private CvMemStorage storage;
    private CvSeq faces;
    
    private BackgroundConsistencyAnalysis consistencyAnalysis = new BackgroundConsistencyAnalysis();
    
    private BackgroundSubtractorMOG2 backgroundSubtractor;
    
    public FaceViewWithAnalysis(Context context) throws IOException {
        super(context);
 
        // Load the classifier file from Java resources.
        File classifierFile = Loader.extractResource(getClass(),
            "/com/googlecode/javacv/facepreview/data/haarcascade_frontalface_alt.xml",
            context.getCacheDir(), "classifier", ".xml");
        if (classifierFile == null || classifierFile.length() <= 0) {
            throw new IOException("Could not extract the classifier file from Java resource.");
        }

        // Preload the opencv_objdetect module to work around a known bug.
        Loader.load(opencv_objdetect.class);
        classifier = new CvHaarClassifierCascade(cvLoad(classifierFile.getAbsolutePath()));
        classifierFile.delete();
        if (classifier.isNull()) {
            throw new IOException("Could not load the classifier file.");
        }
        storage = CvMemStorage.create();
        
        backgroundSubtractor = new BackgroundSubtractorMOG2(); 
    }
    
    public void onPreviewFrame(final byte[] data, final Camera camera) {
        try {
            Camera.Size size = camera.getParameters().getPreviewSize();
            processImage(data, size.width, size.height);
            camera.addCallbackBuffer(data);
        } catch (RuntimeException e) {
            // The camera has probably just been released, ignore.
        	System.err.println(e.toString());
        }
    }

    public interface FaceViewImageCallback {
    	void image(IplImage image /*BGR*/);
    }
    public void setFaceViewImageCallback(FaceViewImageCallback callback) {
    	mCallback = callback;
    }
    FaceViewImageCallback mCallback = null;
    
    private void createSubsampledImage(byte[] data, int width, int height, int f, IplImage subsampledImage) {
    	// TODO: speed this up
        int imageWidth  = subsampledImage.width();
        int imageHeight = subsampledImage.height();
        int dataStride = f*width;
        int imageStride = subsampledImage.widthStep();
        ByteBuffer imageBuffer = subsampledImage.getByteBuffer();
        for (int y = 0; y < imageHeight; y++) {
            int dataLine = y*dataStride;
            int imageLine = y*imageStride;
            for (int x = 0; x < imageWidth; x++) {
                imageBuffer.put(imageLine + x, data[dataLine + f*x]);
            }
        }
    }
    
    // on main thread 
    // the following could be pipelined (via ThreadPoolExecutor)
    // TODO: this more efficienty using built in API, or parallel for http://stackoverflow.com/questions/4010185/parallel-for-for-java
    protected void processImage(byte[] data, int width, int height) {
    	if (grayImage == null || grayImage.width() != width/CONSISTENCY_SUBSAMPLING_FACTOR || grayImage.height() != height/CONSISTENCY_SUBSAMPLING_FACTOR) {
        	try {
        		grayImage = IplImage.create(width/CONSISTENCY_SUBSAMPLING_FACTOR, height/CONSISTENCY_SUBSAMPLING_FACTOR, IPL_DEPTH_8U, 1);
        	} catch (Exception e) {
        		// ignore exception. It is only a warning in this case
        		System.err.println(e.toString());
        	}
        }
    	createSubsampledImage(data, width, height, CONSISTENCY_SUBSAMPLING_FACTOR, grayImage);
        
        // TODO: see if this callback is on the UI thread... if not, then the
        // below asynchronous thing probably shouldn't be asynchronous
        // or maybe not.. Perhaps we want 
        
        if (foreground == null) {
			foreground = IplImage.create(grayImage.width(),
					grayImage.height(), IPL_DEPTH_8U, 1);
		}
        
        // this function has linear variance
        final double learningRate = 0.05;
        backgroundSubtractor.apply(grayImage, foreground, learningRate);
        
        // This callback only needs to be executed every few seconds. For this particular callback, we could do a larger subsampling
        if (mCallback != null) {
        	if (largerGrayImage == null || largerGrayImage.width() != width/RECOGNITION_SUBSAMPLING_FACTOR || largerGrayImage.height() != height/RECOGNITION_SUBSAMPLING_FACTOR) {
            	try {
            		largerGrayImage = IplImage.create(width/RECOGNITION_SUBSAMPLING_FACTOR, height/RECOGNITION_SUBSAMPLING_FACTOR, IPL_DEPTH_8U, 1);
            	} catch (Exception e) {
            		// ignore exception. It is only a warning in this case
            		System.err.println(e.toString());
            	}
            }
        	createSubsampledImage(data, width, height, RECOGNITION_SUBSAMPLING_FACTOR, largerGrayImage);
            if (debugPictureCount == 0) {
            	//debugPrintIplImage(grayImage, this.getContext());
            }
            mCallback.image(grayImage);
        }

   		// detect face
		cvClearMemStorage(storage);
		faces = cvHaarDetectObjects(grayImage, classifier, storage, 1.1, 3, CV_HAAR_FIND_BIGGEST_OBJECT);
        
	    // This is only needed for display reasons
        if (forgroundBitmap == null) {
   			forgroundBitmap = Bitmap.createBitmap(grayImage.width(), grayImage.height(), Config.ALPHA_8);
        }
   		forgroundBitmap.copyPixelsFromBuffer(foreground.getByteBuffer());
   		consistencyAnalysis.processNewFrame(foreground.getByteBuffer(), forgroundBitmap.getHeight(), forgroundBitmap.getWidth(), new CvRect(cvGetSeqElem(faces, 0)));
   		postInvalidate();
    }
    
    // todo: delete
    static int debugPictureCount = 0;
    private static void debugPrintIplImage(IplImage src, Context context) {
    	File file = new File(context.getExternalFilesDir(null), "testimage_same.jpg");
    	cvSaveImage(file.getAbsolutePath(), src);
    	debugPictureCount++;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setTextSize(20);
        
        float textWidth = paint.measureText(displayedText);
        canvas.drawText(displayedText, (getWidth()-textWidth)/2, 20, paint);

        // show motion tracking, makes for a cool demo
        if (forgroundBitmap != null) {
        	paint.setColor(Color.BLACK);
            paint.setStrokeWidth(0);
            canvas.drawRect(0, 0, forgroundBitmap.getWidth(), forgroundBitmap.getHeight(), paint);
            paint.setColor(Color.WHITE);
        	canvas.drawBitmap(forgroundBitmap, new Matrix(), paint);
        }
        
        consistencyAnalysis.drawChartCMD(canvas, paint);
        
        paint.setStrokeWidth(2);
        paint.setColor(Color.RED);
        
        if (faces != null) {
            paint.setStrokeWidth(2);
            paint.setStyle(Paint.Style.STROKE);
            float scaleX = (float)getWidth()/grayImage.width();
            float scaleY = (float)getHeight()/grayImage.height();
            int total = faces.total();
            for (int i = 0; i < total; i++) {//should only be 1
                CvRect r = new CvRect(cvGetSeqElem(faces, i));
                int x = r.x(), y = r.y(), w = r.width(), h = r.height();
                //Commented out code works if using back facing camera
                //canvas.drawRect(x*scaleX, y*scaleY, (x+w)*scaleX, (y+h)*scaleY, paint);
                canvas.drawRect(getWidth()-x*scaleX, y*scaleY, getWidth()-(x+w)*scaleX, (y+h)*scaleY, paint);
            }
        }   

    }
}
