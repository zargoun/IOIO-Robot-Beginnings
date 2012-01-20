package ioio.examples.simple;

//TODO Figure out why only the left motor is running
//TODO Figure out why the orientation being reported changes axis between screen turn on and off
//TODO Contain motor controller, sensor and PID in one class. Multithread?
//TODO Why is the motor so jerky at low pwm?

//import ioio.lib.api.AnalogInput;
import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
//import ioio.lib.api.PwmOutput;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.AbstractIOIOActivity;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.util.Log;
//Libraries for accessing the sensors
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.hardware.SensorEventListener;
//Libraries for creating a motor driver class
import tweeter0830.TB661Driver.TB661Driver;
import tweeter0830.pidcontrol.PID;

public class IOIOSimpleApp extends AbstractIOIOActivity {
	private TextView textView_;
	private TextView loopRateView_;
	private SeekBar seekBar_;
	private ToggleButton toggleButton_;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        textView_ = (TextView)findViewById(R.id.TextView);
        loopRateView_ = (TextView)findViewById(R.id.loopRateVal);
        seekBar_ = (SeekBar)findViewById(R.id.SeekBar);
        toggleButton_ = (ToggleButton)findViewById(R.id.ToggleButton);
        

        enableUi(false);
    }
	
	class IOIOMotoThread extends AbstractIOIOActivity.IOIOThread {
		private DigitalOutput led_;
		private SensorManager sm_;
		private Sensor accelSensor_;
		private Sensor magSensor_;
		private AccelListener accelListener_;
		private PID turnPID_;
		private TB661Driver motorDriver_ = null;
		
		float[] accelVector_ = new float[3];
		float[] magVector_ = new float[3];
		
		long lastLoopTime_;
		long thisLoopTime_;
		double loopRate_;
		
		public void setup() throws ConnectionLostException {
			try {
				lastLoopTime_ = System.nanoTime();
				
				//Get the sensor manager object
				sm_ = (SensorManager) getSystemService(SENSOR_SERVICE);
				//Get a sensor object for the accelerometer
				accelSensor_ = sm_.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
				magSensor_ = sm_.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
				accelListener_ = new AccelListener();
				sm_.registerListener(accelListener_, accelSensor_, SensorManager.SENSOR_DELAY_FASTEST);
				sm_.registerListener(accelListener_, magSensor_, SensorManager.SENSOR_DELAY_FASTEST);
				
				led_ = ioio_.openDigitalOutput(IOIO.LED_PIN, true);
				motorDriver_ = new TB661Driver();
				motorDriver_.setMotor(1, 10, 11, 3, 6, 100, ioio_ );
				motorDriver_.setMotor(2, 12, 13, 4, 6, 100, ioio_ );
				motorDriver_.powerOn();
				motorDriver_.moveForward(1, 1);
				sleep(500);
				motorDriver_.move(3, 0);
				turnPID_ = new PID(1, 0, 0);
				turnPID_.setSetpoint(0);
				
				Log.d("Setup", "Got to the end of setup\n");
				enableUi(true);
			} catch (ConnectionLostException e) {
				enableUi(false);
				throw e;
			} catch (InterruptedException e) {
				sm_.unregisterListener(accelListener_);
				ioio_.disconnect();
			}
		}
		
		public void loop() throws ConnectionLostException {
			try {
				float azOrientation = getAzOrientation(accelVector_, magVector_);
				long currentTime = System.nanoTime();
				double[] motorSpeeds = new double[2];
				Log.d("Loop", "First PID loop?" + turnPID_.isInitialized() + "\n");
				if( !turnPID_.isInitialized() ){
					turnPID_.firstStep(getAzOrientation(accelVector_, magVector_), currentTime);
				} else {
					turnPID_.updateProcessVar(azOrientation, currentTime);
					double turnOut = turnPID_.updateOutput();
					led_.write(!toggleButton_.isChecked());
					motorSpeeds = mapPIDOutputToMotor(turnOut, 0);
					Log.d("Loop", "Motor1: " + motorSpeeds[0] + " Motor2: "+ motorSpeeds[1] + "\n");
					motorDriver_.move(1,motorSpeeds[0]);
					motorDriver_.move(2,motorSpeeds[1]);
					setText(Float.toString(azOrientation), Double.toString(turnOut));
				}
				sleep(10);
			} catch (InterruptedException e) {
				sm_.unregisterListener(accelListener_);
				ioio_.disconnect();
			} catch (ConnectionLostException e) {
				enableUi(false);
				throw e;
			}
		}
		
		private double[] mapPIDOutputToMotor(double pidOutput, double speed){

			double leftSpeed = speed - pidOutput;
			double rightSpeed = speed + pidOutput;
			double extraTurn;
			double[] motorSpeeds = new double[2];
			
			if( rightSpeed > 1)
			{
				extraTurn = rightSpeed - 1;
				rightSpeed = 1;
				leftSpeed = leftSpeed - extraTurn;
			} else if( leftSpeed > 1){
				extraTurn = leftSpeed - 1;
				leftSpeed = 1;
				rightSpeed = rightSpeed - extraTurn;
			}else if( rightSpeed < -1) {
				extraTurn = rightSpeed + 1;
				rightSpeed = -1;
				leftSpeed = leftSpeed + extraTurn;
			}else if( leftSpeed < -1) {
				extraTurn = leftSpeed + 1;
				leftSpeed = -1;
				rightSpeed = rightSpeed + extraTurn;
			}
			motorSpeeds[0] = leftSpeed;
			motorSpeeds[1] = rightSpeed;
			return motorSpeeds;
			
		}
		
		private float getAzOrientation( float[] accelArray, float[] magArray){
			float[] rotMatrix = new float[9];
			float[] magIncMatrix = new float[9];
			float[] returnVals = new float[3];
			
			SensorManager.getRotationMatrix(rotMatrix, magIncMatrix, accelArray, magArray);
			returnVals = SensorManager.getOrientation(rotMatrix, magIncMatrix);
			
			return returnVals[0];
		}
		
		private void updateLoopRate(){
			thisLoopTime_ = System.nanoTime();
			loopRate_ = 1/( (double)(thisLoopTime_-lastLoopTime_)/1000000000L);
		}
		
		private void updateLoopTimes(){
			lastLoopTime_ = thisLoopTime_;
		}
		
		private class AccelListener implements SensorEventListener{
			@Override
			public void onAccuracyChanged(Sensor sensor, int accuracy){
			}

			@Override
			public void onSensorChanged(SensorEvent event) {
				if( event.sensor.getType() == Sensor.TYPE_ACCELEROMETER){
					accelVector_[0] = event.values[0];
					accelVector_[1] = event.values[1];
					accelVector_[2] = event.values[2];
				}
				else if(event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
				{
					magVector_[0] = event.values[0];
					magVector_[1] = event.values[1];
					magVector_[2] = event.values[2]; 
				}
			}
		}
	}

	@Override
	protected AbstractIOIOActivity.IOIOThread createIOIOThread() {
		return new IOIOMotoThread();
	}

	private void enableUi(final boolean enable) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				seekBar_.setEnabled(enable);
				toggleButton_.setEnabled(enable);
			}
		});
	}
	
	private void setText(final String str1) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				textView_.setText(str1);
			}
		});
	}
	
	private void setText(final String str1, final String str2) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				textView_.setText(str1);
				loopRateView_.setText(str2);
			}
		});
	}
	
} 