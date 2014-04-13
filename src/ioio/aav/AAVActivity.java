package ioio.aav;

import ioio.lib.api.AnalogInput;
import ioio.lib.api.PwmOutput;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.BaseIOIOLooper;
import ioio.lib.util.IOIOLooper;
import ioio.lib.util.android.IOIOActivity;

import java.util.ArrayList;
import java.util.List;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.JavaCameraView;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

public class AAVActivity extends IOIOActivity implements CvCameraViewListener2 {
	private static final String _TAG = "AAVActivity";

	private Mat _rgbaImage;

	private JavaCameraView _openCvCameraView;
	private MainController _mainController;

	// private boolean isTransmitting = false;
	// private boolean isIOIOConnected = false;
	// public static SensorFusion mSensorFusion = null;

	static final double MIN_CONTOUR_AREA = 100;
	
	volatile double _currentContourArea = 7;	
	volatile Point _currentCenterPoint = new Point(-1, -1);
	Point _screenCenterCoordinates = new Point(-1, -1);
	int _countOutOfFrame = 0;
	int _pwmThresholdCounter = 0;

	Mat _HsvMat;
	Mat _processedMat;
	Mat _dilatedMat;

	private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
		@Override
		public void onManagerConnected(int status) {
			switch (status) {
			case LoaderCallbackInterface.SUCCESS: {
				_openCvCameraView.enableView();
				_HsvMat = new Mat();
				_processedMat = new Mat();
				_dilatedMat = new Mat();
			}
				break;
			default: {
				super.onManagerConnected(status);
			}
				break;
			}
		}
	};

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		// Get a reference to the sensor service
		// SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

		setContentView(R.layout.main);

		_openCvCameraView = (JavaCameraView) findViewById(R.id.iron_track_activity_surface_view);
		_openCvCameraView.setCvCameraViewListener(this);

		_openCvCameraView.setMaxFrameSize(176, 144);

		// mSensorFusion = new SensorFusion(sensorManager);

		_mainController = new MainController();

		_countOutOfFrame = _pwmThresholdCounter = 0;
	}

	@Override
	public void onPause() {
		super.onPause();
		if (_openCvCameraView != null)
			_openCvCameraView.disableView();

		// Unregister sensor listeners to prevent the activity from draining the device's battery.
		// mSensorFusion.unregisterListeners();
	}

	@Override
	public void onResume() {
		super.onResume();
		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_7, this, mLoaderCallback);

		// Restore the sensor listeners when user resumes the application.
		// mSensorFusion.initListeners();
	}

	public void onDestroy() {
		super.onDestroy();
		if (_openCvCameraView != null)
			_openCvCameraView.disableView();

		// unregister sensor listeners to prevent the activity from draining the device's battery.
		// mSensorFusion.unregisterListeners();
	}

	public void onCameraViewStarted(int width, int height) {
		_rgbaImage = new Mat(height, width, CvType.CV_8UC4);
		_screenCenterCoordinates.x = _rgbaImage.size().width / 2;
		_screenCenterCoordinates.y = _rgbaImage.size().height / 2;
	}

	public void onCameraViewStopped() {
		_rgbaImage.release();
		_currentCenterPoint.x = -1;
		_currentCenterPoint.y = -1;
		_mainController.reset();
	}

	public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
		synchronized (inputFrame) {

			_rgbaImage = inputFrame.rgba();
			
			double contourArea;

			// In contrast to the C++ interface, Android API captures images in the RGBA format.
			Imgproc.cvtColor(_rgbaImage, _HsvMat, Imgproc.COLOR_RGB2HSV_FULL);
			// Imgproc.cvtColor(_rgbaImage, _HsvMat, Imgproc.COLOR_RGB2YCrCb);

			Core.inRange(_HsvMat, new Scalar(60, 100, 30), new Scalar(130, 255, 255), _processedMat); // Green ball

			Imgproc.dilate(_processedMat, _dilatedMat, new Mat());
			final List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
			Imgproc.findContours(_dilatedMat, contours, new Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
			MatOfPoint2f points = new MatOfPoint2f();
			_currentContourArea = 7;
			for (int i = 0, n = contours.size(); i < n; i++) {
				contourArea = Imgproc.contourArea(contours.get(i));
				if (contourArea > _currentContourArea) {
					_currentContourArea = contourArea;
					//
					// contours.get(x) is a single MatOfPoint, but to use minEnclosingCircle we need to pass a MatOfPoint2f so we need to do a conversion
					//
					contours.get(i).convertTo(points, CvType.CV_32FC2);
				}
			}
			if (!points.empty() && _currentContourArea > MIN_CONTOUR_AREA) {
				Imgproc.minEnclosingCircle(points, _currentCenterPoint, null);
//				Core.circle(_rgbaImage, _currentCenterPoint, 3, new Scalar(255, 0, 0), Core.FILLED);
				// Core.circle(_rgbaImage, _currentCenterPoint, (int) Math.round(Math.sqrt(_currentContourArea / Math.PI)), new Scalar(255, 0, 0), 3, 8, 0);//Core.FILLED);
			}

			/*
			 * double area = 1; Mat circles = new Mat(); Imgproc.GaussianBlur(_processedMat, _processedMat, new Size(9, 9), 2, 2); Imgproc.HoughCircles(_processedMat, circles,
			 * Imgproc.CV_HOUGH_GRADIENT, 2, _processedMat.rows() / 4, 100, 50, 10, 100);
			 * 
			 * /// Draw the circles detected int rows = circles.rows(); int elemSize = (int) circles.elemSize(); // Returns 12 (3 * 4bytes in a float) float[] circleData = new float[rows * elemSize /
			 * 4];
			 * 
			 * if (circleData.length > 0) { circles.get(0, 0, circleData); // Points to the first element and reads the whole thing into circleData int radius = 0; for (int i = 0; i <
			 * circleData.length; i = i + 3) { _currentCenterPoint.x = circleData[i]; _currentCenterPoint.y = circleData[i + 1]; radius = (int) Math.round(circleData[2]); area = Math.PI * (radius *
			 * radius); if (area > _currentContourArea) { _currentContourArea = area; } } if (_currentContourArea > MIN_CONTOUR_AREA) Core.circle(_rgbaImage, _currentCenterPoint, radius, new Scalar(255, 0, 0), 3); }
			 */

			contours.clear();

		}
		return _rgbaImage;
	}

	/**
	 * This is the thread on which all the IOIO activity happens. It will be run every time the application is resumed and aborted when it is paused. The method setup() will be called right after a
	 * connection with the IOIO has been established (which might happen several times!). Then, loop() will be called repetitively until the IOIO gets disconnected.
	 */
	class Looper extends BaseIOIOLooper {

		private PwmOutput _pwmPan;
		private PwmOutput _pwmTilt;
		private PwmOutput _pwmMotor;
		private PwmOutput _pwmFrontWheels;

		private double[] _pwmValues = new double[4];

		// IRs
		private AnalogInput _frontLeftIR, _frontRightIR, _rightSideIR, _leftSideIR;
		
		private static final int BUFFER_SIZE = 256;  //Always set the buffer to something bigger that sample size otherwise you'd lose data.
		
		boolean isBacking = false;

		/**
		 * Called every time a connection with IOIO has been established. Typically used to open pins.
		 * 
		 * @throws ConnectionLostException
		 *             When IOIO connection is lost.
		 * @throws InterruptedException
		 * 
		 * @see ioio.lib.util.AbstractIOIOActivity.IOIOThread#setup()
		 */
		@Override
		protected void setup() throws ConnectionLostException, InterruptedException {

			try {
				_pwmValues = _mainController.getPWMValues();

				_pwmPan = ioio_.openPwmOutput(10, 100); // 9 shield
				_pwmTilt = ioio_.openPwmOutput(6, 100); // 5 shield
				_pwmMotor = ioio_.openPwmOutput(27, 100); // screw terminal
				_pwmFrontWheels = ioio_.openPwmOutput(12, 100); // 11 shield
				
				_frontLeftIR = ioio_.openAnalogInput(40);  // A/D 1 shield
				_leftSideIR = ioio_.openAnalogInput(41);  // A/D 2 shield
				_frontRightIR = ioio_.openAnalogInput(43); // A/D 3 shield
				_rightSideIR = ioio_.openAnalogInput(42); // A/D 4 shield
				
				
//				_frontLeftIR.setBuffer(BUFFER_SIZE);
//				_frontRightIR.setBuffer(BUFFER_SIZE);
//				_leftSideIR.setBuffer(BUFFER_SIZE);
//				_rightSideIR.setBuffer(BUFFER_SIZE);

				
				// TO TEST WITH IOIO ONLY - REMOVE ONCE EVERYTHING WORKS
//				_frontLeftIR = ioio_.openAnalogInput(42);
//				_frontRightIR = ioio_.openAnalogInput(41);
//				_leftSideIR = ioio_.openAnalogInput(43);
//				_rightSideIR = ioio_.openAnalogInput(44);
				
				
				_pwmPan.setPulseWidth((int) _pwmValues[0]);
				_pwmTilt.setPulseWidth((int) _pwmValues[1]);
				_pwmMotor.setPulseWidth((int) _pwmValues[2]);
				_pwmFrontWheels.setPulseWidth((int) _pwmValues[3]);
//				_mainController.irSensors.setIRSensorsVoltage(_frontLeftIR.getVoltage(), _frontRightIR.getVoltage(), _leftSideIR.getVoltage(), _rightSideIR.getVoltage());
			} catch (ConnectionLostException e) {
				Log.e(_TAG, e.getMessage());
				throw e;
			}
		}

		/**
		 * Called repetitively while the IOIO is connected.
		 * 
		 * @throws ConnectionLostException
		 *             When IOIO connection is lost.
		 * 
		 * @see ioio.lib.util.AbstractIOIOActivity.IOIOThread#loop()
		 */
		@Override
		public void loop() throws ConnectionLostException {

			try {
				synchronized (_mainController) {
					
				if (_currentContourArea > MIN_CONTOUR_AREA) {
//					isBacking = _mainController.irSensors.isBacking(_frontLeftIR.getVoltage(), _frontRightIR.getVoltage(), _leftSideIR.getVoltage(), _rightSideIR.getVoltage());
					
					_mainController.calculatePanTiltPWM(_screenCenterCoordinates, _currentCenterPoint);
					
					_mainController.irSensors.updateIRSensorsVoltage(_frontLeftIR.getVoltage(), _frontRightIR.getVoltage(), _leftSideIR.getVoltage(), _rightSideIR.getVoltage());
					if (_pwmThresholdCounter > 8) {
						_mainController.calculateMotorPWM(_currentContourArea);
						_pwmThresholdCounter = 0;
					} else {
						_pwmThresholdCounter++;

//						if (_mainController.irSensors.checkIRSensors())
//							_pwmThresholdCounter = 10;
				    }
					
//					if (_frontLeftIR.available() == BUFFER_SIZE) {
//						_mainController.irSensors.setIRSensorsVoltage(getAverageVoltage(_frontLeftIR), getAverageVoltage(_frontRightIR), getAverageVoltage(_leftSideIR), getAverageVoltage(_rightSideIR));
//					}
					
//					_mainController.irSensors.setIRSensorsVoltage(_frontLeftIR.getVoltage(), _frontRightIR.getVoltage(), _leftSideIR.getVoltage(), _rightSideIR.getVoltage());
					
//					if (_mainController.irSensors.isBacking()) {
//						_mainController.calculatePanTiltPWM(_screenCenterCoordinates, _currentCenterPoint);
//					} else {
//						// MUST BE IN THIS ORDER
//						_mainController.calculatePanTiltPWM(_screenCenterCoordinates, _currentCenterPoint);
////						_mainController.calculateWheelsPWM();
//						_mainController.calculateMotorPWM(_currentContourArea);
//						_mainController.irSensors.checkIRSensors();
//					}
					_countOutOfFrame = 0;
				} else {
					_countOutOfFrame++;
					if (_countOutOfFrame > 20) {
						_mainController.reset();
						_countOutOfFrame = _pwmThresholdCounter = 0;
					}
				}
				
				_pwmValues = _mainController.getPWMValues();

				_pwmPan.setPulseWidth((int) _pwmValues[0]);
				_pwmTilt.setPulseWidth((int) _pwmValues[1]);
				_pwmFrontWheels.setPulseWidth((int) _pwmValues[3]);

				// for going between backwards and forwards
				// WARNING: MINIMIZE GOING FROM FULL SPEED FORWARD TO FULL SPEED BACKWARDS
				// DOING SO CAN DAMAGE THE GEARS
//				if ((int) _pwmValues[2] < MainController.STOP_MOTOR_PWM)
//					_pwmMotor.setPulseWidth(MainController.STOP_MOTOR_PWM);

//				Log.e("_pwmValues[2]", String.valueOf(_pwmValues[2]));
				
				_pwmMotor.setPulseWidth((int) _pwmValues[2]);
				}
				Thread.sleep(8);

			} catch (InterruptedException e) {
				ioio_.disconnect();
			}
		}

		@Override
		public void disconnected() {
			_pwmPan.close();
			_pwmTilt.close();
			_pwmMotor.close();
			_pwmFrontWheels.close();
		}
		
		private float getAverageVoltage(AnalogInput analogInput) throws InterruptedException, ConnectionLostException {
			float average = 0.0f;
			for (int i=0; i < 25; i++) {
				 average += analogInput.getVoltageBuffered();
//				 Log.e("analogInput.getVoltageBuffered()", String.valueOf(analogInput.getVoltageBuffered()));
			 }
//			 Log.e("average/25", String.valueOf(average/25));
			 return average/25;
		}
	}

	/**
	 * A method to create our IOIO thread.
	 * 
	 * @see ioio.lib.util.AbstractIOIOActivity#createIOIOThread()
	 */
	@Override
	protected IOIOLooper createIOIOLooper() {
		return new Looper();
	}
}