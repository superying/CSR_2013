package falldetection.hig.no;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.TimeZone;
import java.util.Vector;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;


/**
* This class starts a service which is similar to a Daemon process that runs
* in the background. The service implements a class listener to access the mobile phone�s accelerometer
* (and possibly other sensors). The actual fall detection algorithm is also found
* in this class. Implementing a service conserves much of the phone�s resources.
* 
* This service uses algorithm 1 and with inactivity detection of at least 0.5 sec.
* 
* @author Ian James Daniel Gonzales
* @version 1.3 
* 
*/

/**
 * Notes: 
 * 
 * 4-5: They use default thresholds for Rising Edge Time. Should these not be dynamically calculated?
 * 4-5: This algorithm only uses the last 2 readings to determine a lot of things. Should we save the last 5-10 readings to get a more accurate picture of what's going on?
 * 
 * @author kevin
 *
 */

public class SensorService extends Service implements SensorEventListener
{
	private static String logMsg="Service 1\n";
	
	private static final String tag = "FallDetection.SensorService";
	private static final boolean debug = true;
		
	/**
	 * This RSS variable holds the default Lower Fall Threshold. 
	 * Basically, any RSS value that falls below this threshold will mark the beginning of a fall. The fall
	 * starts when the RSS value falls down towards 0G in a free fall.
	 * 
	 * Note: Default thresholds for Algorithm 1. Pseudo code on Pg. 43  
	 */
	private static final float LFT_MAX = (float) 6.6700; //RSS <= LFT_MAX (.679 g)
	//private static final float LFT_MAX = (float) 0.4*SensorManager.GRAVITY_EARTH;
	//private static final float LFT_MAX = (float) 0.2*SensorManager.GRAVITY_EARTH;
	/**
	 * This RSS variable holds the default Upper Fall Threshold. This is the rapid change in vertical acceleration opposite to gravity.
	 * Basically, any RSS value that falls above this threshold will mark impact with the ground.
	 * 
	 * Note: Default thresholds for Algorithm 1. Pseudo code on Pg. 43
	 */
	
	//private static final float UFT_MIN = (float) 15.7600; //RSS >= UTF_MIN (i.e., 1.6g)
	
	private static final float UFT_MIN = (float) 19.62; //RSS >= UTF_MIN (i.e., 2g) Used in SensorService2.
	
	//private static final float UFT_MIN = (float) 24.525; //RSS >= UTF_MIN (i.e., 2.5g) Used in SensorService3.
	
	/**
	 * This variable holds the time, in microseconds, when the Upper Fall Threshold was first exceeded.
	 * Rising Edge Time is the time, measured in microseconds, for when the Lower Fall Threshold is last exceeded until the Upper Fall Threshold is exceeded.
	 * 
	 * UFT_timestamp - LFT_timestamp <= TRE_MAX
	 */
	private static final long TRE_MAX = 366000;
	/**
	 * This variable holds the time, in microseconds, when the Lower Fall Threshold was last exceeded.
	 * Rising Edge Time is the time, measured in microseconds, for when the Lower Fall Threshold is last exceeded until the Upper Fall Threshold is exceeded.
	 * 
	 * UFT_timestamp - LFT_timestamp >= TRE_MIN
	 */
	private static final long TRE_MIN = 102000;
	
	/**
	 * This variable holds the time, in microseconds, when the Lower Fall Threshold was last exceeded. It is used
	 * in calculating the Rising-Edge Time which is the time from when the LFT is last exceeded until UFT is exceeded.
	 */
	private static float TRE_start;
	/**
	 * This variable holds the time, in microseconds, when the Upper Fall Threshold was first exceeded. It is used
	 * in calculating the Rising-Edge Time which is the time from when the LFT is last exceeded until UFT is exceeded.
	 */
	private static float TRE_end;
	
	/**
	 * This variable holds the time, in microseconds, when the Lower Fall Threshold was last exceeded. It is used 
	 * in calculating the Falling-Edge Time which is the time from when the RSS signal last goes below the LFT 
	 * until it reaches the UFT. 
	 */
	private static float TFE_start;
	/**
	 * This variable holds the time, in microseconds, when the Upper Fall Threshold was first exceeded. It is used 
	 * in calculating the Falling-Edge Time which is the time from when the RSS signal last goes below the LFT 
	 * until it reaches the UFT.
	 */
	private static float TFE_end;
	
	
	
	private static KVector curr_vector;
	private static KVector prev_vector;
	
	/**
	 * This is the scalar orientation between curr_ori and prev_ori.
	 */
	private static float curr_ori;
	/**
	 * This holds the value of the last calculated orientation.
	 */
	private static float prev_ori;
	
	/**
	 * This variable holds the number of consecutive scalar product readings less than 3 in order to measure inactivity.  
	 * 
	 * It uses the scalar product between 2 consecutive readings to measure inactivity. If in at least 17 consecutive acceleration readings,
	 * the scalar product is lower than 3 degrees (depicting none or minuscule change between the readings), then
	 * a period of inactivity is detected. This is set to .51 seconds. 
	 */
	private static int inactivityCounter2;
	
	//This variable holds the number of times determineInactivityPeriod() has been called since a potential fall was last detected.
	private static int inactivityCounter1;
	
	/**
	 * This variable is true if a period of inactivity has been detected, false otherwise. 
	 * See 'inactivityCounter2' variable for definition of an inactivity period.
	 */
	private static boolean inactivityDetected = false;
	private static long inactivity_timestamp;
	
	
	/**
	 * This holds the timestamp, in microseconds, when the Lower Fall Threshold was determined to have happened.
	 */
	private static long LFT_timestamp;
	/**
	 * This holds the timestamp, in microseconds, when the Upper Fall Threshold was determined to have happened.
	 */
	private static long UFT_timestamp;
	/**
	 * This holds the timestamp, in microseconds, when the fall was detected.
	 */
	private static long timestamp_fall_detected;
	
	/**
	 * This is a grow-able array that holds the Lower Fall Threshold time-stamps 
	 */
	private static Vector lft_timestamps;
	
	//private static ArrayList<KVector> vectorArray;
	
	private static SensorManager sm;
	private static List<Sensor> sensorAcc;
	
	//set to true when the Sensor Service is currently running.
	private static boolean isRunning = false;
	//set to true when a fall is detected.
	private static boolean detectedFall = false;
	//set to true when the lower fall threshold is exceeded.
	private static boolean LFT_exceeded = false;
	//set to true when the upper fall threshold is exceeded.
	private static boolean UFT_exceeded = false;
	
	
	private static SensorManager sensorManager;
	private static Sensor accelerometer;
	
	//This linked list is used to store the vectors needed to check for ADLs.
	private static LinkedList <KVector> vectorList=new LinkedList<KVector>();
	
	//set to true if a class A or B ADL is detected
	private static boolean ADLTypeAB=false;
	//set to true if a class C ADL is detected
	private static boolean ADLTypeC=false;
	
	//set to true if a new potential fall has been detected since we last checked for ADLs
	private static boolean newPotentialFallForADLs=false;
	//set to true if we have checked for ADLs since the last possible fall was detected
	private static boolean checkedADLs=false;
	
	//If the AAMV is less than this threshold, we have a class A or B ADL. Otherwise, it's a real fall.
	private static final float AAMV_MIN=2.646f;
	//If the FFI is greater than FFI_MAX and FFAAM is less than FFAAM_MIN, we have a class C ADL. 
	//Otherwise, it's a real fall.
	private static final int FFI_MAX=100;
	private static final double FFAAM_MIN=0.5*SensorManager.GRAVITY_EARTH;
	
	//1 millisecond=1.0*10^(3) microseconds
	public static final double MICRO_TO_MILLI= 0.001;
	
	private static Boolean testing=false;
	
	
	public SensorService() 
	{
		super();
		if (!isRunning) 
			isRunning = true;
		
		if (debug) 
		{
			Log.i(tag, "SensorService() - start");
		}
	}
	
	@Override
	public void onCreate()
	{
		if (debug) 
		{
			Log.i(tag, "onCreate()");
		}
		
		curr_vector = new KVector();
		prev_vector = new KVector();
		
		inactivityCounter1=0;
		inactivityCounter2=0;
		inactivity_timestamp = 0;
		timestamp_fall_detected=0;
		curr_ori = 0;
		prev_ori = 0;
		
		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		accelerometer = (Sensor) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		
		//UI is a 60ms sampling rate.
		sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
		
		
				
	}
	
	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) 
	{
		//Auto-generated method stub
		//if (debug) 
			//Log.i(tag, "SensorListener.onAccuracyChanged() -> " + accuracy);
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) 
	{
	    // We want this service to continue running until it is explicitly
	    // stopped, so return sticky.
	    return START_NOT_STICKY;
	}
	
	public void onDestroy()
	{
		sensorManager.unregisterListener(this);
		// Tell the user we stopped.
        //Toast.makeText(this, "Sensor Service Stopped", Toast.LENGTH_SHORT).show();
        //logMsg+="Sensor Service Stopped";
	}
	
	//These variables are used for the linear interpolation...
	KVector vectorFirst=new KVector();
	KVector vectorSecond=new KVector();
	KVector interpolatedVector=new KVector();
	
	@Override
	public void onSensorChanged(final SensorEvent se) {
		long timestamp = (long) se.timestamp / 1000;
		
		if (se.sensor.getType() != Sensor.TYPE_ACCELEROMETER)
		{
			if(debug)
				Log.e(tag, "Sensor not accelerometer: " + se.sensor.getType());
		}
		
		
		if (se.sensor.getType()==Sensor.TYPE_ACCELEROMETER)
		{
			//save the last vector
			vectorFirst.set(vectorSecond);
												
			/**
			 * All values are in SI units (m/s^2)
			 * 	values[0]: Acceleration minus Gx on the x-axis
			 * 	values[1]: Acceleration minus Gy on the y-axis
			 * 	values[2]: Acceleration minus Gz on the z-axis 
			*/
			vectorSecond.set(se.values[0], se.values[1], se.values[2]);
			vectorSecond.setTimeStamp(timestamp);
			
			
			if(vectorFirst.getTimeStamp() <= 0) {
				vectorFirst.setTimeStamp(timestamp);
				onNewReading(vectorSecond);
			}
			
			else{
				interpolatedVector=new KVector();
				float interpolatedX=vectorFirst.getX() + (vectorSecond.getX() - vectorFirst.getX())/2;
				float interpolatedY=vectorFirst.getY() + (vectorSecond.getY() - vectorFirst.getY())/2;
				float interpolatedZ=vectorFirst.getZ() + (vectorSecond.getZ() - vectorFirst.getZ())/2;
				
				interpolatedVector.set(interpolatedX, interpolatedY, interpolatedZ);
				interpolatedVector.setTimeStamp(vectorFirst.getTimeStamp() + (vectorSecond.getTimeStamp()-vectorFirst.getTimeStamp())/2);
				onNewReading(interpolatedVector);
				onNewReading(vectorSecond);
			}
			
		}
	}
	
	private void onNewReading(KVector newVector)
	{
		//se.timestamp returns a time in nanoseconds. Why divide it by 1000? The timestamps ought to be in nanoseconds, not microseconds...
		
		//save the last vector
		prev_vector.set(curr_vector);
				
		/**
		 * All values are in SI units (m/s^2)
		 * 	values[0]: Acceleration minus Gx on the x-axis
		 * 	values[1]: Acceleration minus Gy on the y-axis
		 * 	values[2]: Acceleration minus Gz on the z-axis 
		*/
		curr_vector.set(newVector);
		
		if(prev_vector.getTimeStamp() <= 0) 
			prev_vector.setTimeStamp(curr_vector.getTimeStamp());
		
		prev_ori = curr_ori;
		//Orientation between position at timestamp x and timestamp (x-1)
		//Note: The first iteration, vectorFirst will be 0;
		curr_ori = curr_vector.orientation(prev_vector);
		
		if(testing){
		storeData();
		}
		
		if(debug)
		{
			//Log.d(tag + ".var_info", "START: " + se.timestamp);
			//Log.i(tag + ".var_info", "Curr Vector: " + curr_vector.toString() + "::Orientation: " + curr_ori);
			//Log.i(tag + ".var_info", "Prev Vector: " + prev_vector.toString() + "::Orientation: " + prev_ori);
		}
		
		//if a possible fall has been detected, we check for inactivity.
		if (detectedFall)
		{
			determineInactivityPeriod();
		}
		
		/*
		 * If an inactivity period has not been detected and it's at or past 5.01 seconds after the time the possible fall was detected 
		 * (i.e., if determineInactivityPeriod() has been called 167 times or more), then we did not detect a real fall. 
		 * Thus we reset both counters and the detectedFall variable.
		 */
		if ((inactivityCounter1>=167) && (!inactivityDetected))
		{
			inactivityCounter2=0;
			inactivityCounter1=0;
			detectedFall=false;
			logMsg+="Inactivity check failed. We do not have a real fall.";
		}
		
		
		checkADLs();
		
		
		//An alarm is issued if a fall was detected followed by inactivity period
		//NOTE: MISSING additional if statement case that starts like this: timestamp
		//the PDF cut it off
		
		if (inactivityDetected && detectedFall && checkedADLs)//(timestamp - timestamp_fall_detected) > 520000) 
		{
			
			
			if((!ADLTypeAB)&&(!ADLTypeC))
			{				
				
					if (debug)
					{
						Log.i(tag + "SensorListener", "Fall Dectected!!!!! -> Alert Class Intent Started:" + (curr_vector.getTimeStamp() - timestamp_fall_detected));
						logMsg+="Fall Dectected!!!!! Time is "+(curr_vector.getTimeStamp())+"\n";
					}
				Intent alertIntent=new Intent(SensorService.this, FallDetectionAlert.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				startActivity (alertIntent);
				
			}
		
			inactivityCounter1=0;
			inactivityCounter2=0;
			timestamp_fall_detected=0;
			inactivityDetected=false;
			detectedFall = false;
			//Do we need a debug message here?
			
		}
		
	
	
	//question: are LFT and UFT ever going to be exceeded at the same time?
	//'cause if not, there is no reason to check both every time
	//also, if the Sensor Type is not accelerometer, should these be run? That seems like it's wasted computations.
	checkLFT();
	checkUFT();
	}
	
	
	/**
	 * This function checks to see if the Lower Fall Threshold has been exceeded. 
	 * If so, it saves the timestamp of the event in an array and sets the 
	 * LFT_Exceeded variable to true. 
	 * @param timestamp
	 */
	private void checkLFT()
	{
		//I took this out because it will never be true. The algorithm right now auto sets the 
		//value of UFT_exceeded to false after the TRE check EVERY time.
		//if(UFT_exceeded)
			//return;
		
		if (curr_vector.getRSS() <= LFT_MAX)
		{
			if (debug) 
			{
				Log.v(tag, "Lower fall threshold exceeded");
			}
			
			if (!LFT_exceeded)
			{
				lft_timestamps = new Vector(1, 1);
				LFT_timestamp = curr_vector.getTimeStamp();
			}
			lft_timestamps.add(curr_vector.getTimeStamp());
			LFT_exceeded = true;
			
			if (debug)
			{
				Log.i(tag, "curr_ori: " + curr_ori + "::curr_vector: " + curr_vector.getRSS() + "::timestamp: " + curr_vector.getTimeStamp());
			}
		}
	}
	
	/**
	 * This function checks to see if the Upper Fall Threshold has been exceeded. 
	 * @param timestamp
	 */
	
	
	private void checkUFT()
	{
		
		/*
		 * even if the LFT was not exceeded, the UFT should be checked because the ADL filtering algorithms rely on information about the last peak. 
		 * (i.e., the last time UFT was exceeded)
		 * 
		 * So I moved the following if statement to after the code that checks the UFT but before the code that checks for a new fall...
		 * 
		 * if(!LFT_exceeded)
		 * return;		
		 */
				
		if ( curr_vector.getRSS() >= UFT_MIN)
		{
			if (debug) 
			{
				Log.v(tag, "Upper fall threshold exceeded!");
			}
			UFT_timestamp = curr_vector.getTimeStamp();
									
			//if the LFT was not exceeded, there's no point in checking for a new fall.
			if(!LFT_exceeded)
				return;
							
			
				UFT_exceeded = true;
			
				Iterator it = lft_timestamps.iterator();
				while( it.hasNext() )
				{
					Long tmp = (Long) it.next();
					//check for bad timestamps
					if(tmp <= 0)
					{
						if(debug)
						{
							Log.e(tag, "Bad Timestamp: " + tmp);
							//logMsg+="Bad Timestamp: " + tmp+"\n";
						}
						continue;
					}
				
					/**
					 * Note: According to their algorithm, this value should be
					 * tmp = UFT_timestamp - LFT_timstamp to give the following
					 * comparisons: tmp >= TRE_MIN and tmp <= TRE_MAX
					 * 
					 * It looks like instead of comparing the values for just the UFT and LFT they
					 * are comparing every timestamp that happened in LFT and succeeding for any one. 
					 */
					tmp = UFT_timestamp - tmp;
				
					//check to see if (UFT_timestamp - tmp) is below the min Rising-Edge Threshold for all the timestamps
					if(tmp < TRE_MIN) { continue; }
					//check to see if (UFT_timestamp - tmp) is above the max Rising-Edge Threshold for all the timestamps 
					if(tmp > TRE_MAX) { continue; }
				
					timestamp_fall_detected = curr_vector.getTimeStamp();
					if (debug)
					{
						Log.v(tag, "Fall Detected: UFT-LFT = " + tmp + "::Time Detected: " + timestamp_fall_detected);
						logMsg+="\nPotential Fall Detected: UFT-LFT = " + tmp + "::Time Detected: " + curr_vector.getTimeStamp()+"\n\n";
					}
				
					detectedFall = true;
				
					//updating variables to be used by method checkADLS()...
					newPotentialFallForADLs=true;
					checkedADLs=false;
					//Note: I added this statement here. There is no reason to keep checking the timestamps when a fall is already detected.
					break;
				}
				
			
				LFT_exceeded = false;
				UFT_exceeded = false;
			
				if (debug)
				{
					Log.i(tag, "UFT:"+(curr_vector.getTimeStamp())+":"+curr_ori+"::"+curr_vector.getRSS()+":::"+(UFT_timestamp-LFT_timestamp));
				}
			}
		
	}
	/**
	 * This function stores all vectors within a certain time window around the last time the UFT was exceeded. 
	 * (The time window is from 640ms before UFT was exceeded until 540ms after the UFT was exceeded. 
	 * Note that it is actually necessary to save one reading past the end of this window in order to calculate the AAMV.)
	 * It then uses the RSS values of those vectors to calculate the average acceleration magnitude vector 
	 * which it compares against a threshold value to determine if we have a class A or B ADL or a real fall.
	 * Finally, it calls the checkClassC() function in order to check for a class C ADL.
	 */
	private void checkADLs()
	{
		//If a new possible fall hasn't been detected, we only need to save the last 640ms worth of data.
		if(!newPotentialFallForADLs)
		{
			//adding the current vector to the end of the list.
			vectorList.add(new KVector(curr_vector));
		   
		   /* Since we're saving only the last 640ms worth of data, while the timestamp of the last (and most recent) vector in the vectorList is more than 640ms later than 
		    * the timestamp of the first (and oldest) vector in the list, we remove the first vector in the vectorLlist. 
		    * (getTimeStamp() returns a time in microseconds; we need a time in milliseconds. So we multiply by MICRO_TO_MILLI to convert to milliseconds.)
		    */
		   while((vectorList.getLast().getTimeStamp()*MICRO_TO_MILLI - vectorList.getFirst().getTimeStamp()*MICRO_TO_MILLI) > 640)
		   {
		      vectorList.removeFirst();
		   }
		}

		//If a new possible fall has been detected, we need to save the 640ms worth of data before the UFT was exceeded until 540ms after the UFT was exceeded.
		else
		{
			
		   /* While the timestamp for when the UFT was exceeded is more than 640ms later than the timestamp of the first (and oldest) vector in the list, 
		    * we remove the first vector in the vectorList. 
			* (getTimeStamp() returns a time in microseconds; we need a time in milliseconds. So we multiply by MICRO_TO_MILLI to convert to milliseconds.)
			*/
		   while((UFT_timestamp*MICRO_TO_MILLI - vectorList.getFirst().getTimeStamp()*MICRO_TO_MILLI) > 640)
		   {
		      vectorList.removeFirst();
		   }
		   
		   //If the difference between the UFT_timestamp and the timestamp of the most recent vector stored in the vectorList is less than or equal to 540ms, 
		   //then we haven't yet stored all the data in the time window plus one reading past the window, and hence we cannot yet check for ADLS.
		   //We add the current vector to the list.
		   if((vectorList.getLast().getTimeStamp()*MICRO_TO_MILLI - UFT_timestamp*MICRO_TO_MILLI)<=540)
		   {
		      vectorList.add(new KVector(curr_vector));
		   }

		   
		   //Else, we have stored all info necessary to calculate AAMV, FFI, and FFAAM, and so we can now test for class A, B, and C ADLs.
		   else
		   
		   {
			   double acc1=0;
			   double acc2=0;
			 
			   //diffTotal will hold the total of all acceleration magnitude variations between the vectors in the vectorList.
			   double diffTotal=0;
		       ListIterator<KVector> itr=vectorList.listIterator();
		       if(itr.hasNext()){acc2=itr.next().getRSS();}
		       
		       while(itr.hasNext())
		       {
		    	   acc1=acc2;
		    	   acc2=itr.next().getRSS();
		    	   diffTotal+=Math.abs(acc2-acc1);
		       }
		       
		       /* The average acceleration magnitude variation is equal to the total of the acceleration magnitude variations 
		        * divided by the number of vectors within the time window. 
		        * (We subtract one from the vectorList's size because the vectorList includes one vector beyond the time window.)
		        */
			   double AAMV=diffTotal / (vectorList.size()-1);
		   
		      if(AAMV < AAMV_MIN){
		         ADLTypeAB=true;
		         if (debug){
					//Toast.makeText(this, "Calculated AAMV. Detected a class A or B ADL.", 1).show();
					Log.i(tag, "AAMV = " + AAMV +"::Time detected: " + curr_vector.getTimeStamp());
					logMsg+="Calculated AAMV. Detected a class A or B ADL. AAMV = "+ AAMV +"::Time detected: " + curr_vector.getTimeStamp()+"\n";
				 }
		      }
		      else	{
		         ADLTypeAB=false;
		         if (debug)	{
					//Toast.makeText(this, "Calculated AAMV. Did not detect a class A or B ADL.", 1).show();
					Log.i(tag, "AAMV = " + AAMV +"::Time calculated: " + curr_vector.getTimeStamp());
					logMsg+="Calculated AAMV. Did not detect a class A or B ADL. AAMV = " + AAMV +"::Time calculated: " + curr_vector.getTimeStamp()+"\n";
				 }
		      }
		      checkTypeC();
		      checkedADLs=true;
		      newPotentialFallForADLs=false;
		      
		      vectorList.add(new KVector(curr_vector));
		   }
		}
	}
	/**
	 * This function calculates the free fall interval (FFI) and free fall average acceleration magnitude (FFAAM)
	 * and tests them against threshold values to determine if we have a class C ADL or a real fall.
	 * It is called in the body of the checkADLs() function. 
	 */
	private void checkTypeC()
	{
		//the landingStart is estimated to be 80ms prior to the time the UFT was last exceeded.
		long landingStart=(long)(UFT_timestamp*MICRO_TO_MILLI)-80;
		//we must now search backwards in time for the leap end, the time at which to begin the search is 20ms before the landingStart.
		long startSearchTime=landingStart-20;
		
		ListIterator<KVector> itr=vectorList.listIterator();

		//after this while loop, the call itr.previous() will return one element past the startSearchTime...
		while((itr.hasNext())&&((itr.next().getTimeStamp()*MICRO_TO_MILLI)<=startSearchTime)) {}

		itr.previous();

		/**
		 * We now search backwards in time, starting from the last vector whose timestamp is less than or equal to startSearchTime, 
		 * and stopping when we find a vector whose acceleration magnitude is greater than or equal to 1g, which is the leap end.
		 */
		double RSS=0;
		while((itr.hasPrevious())&&((RSS=itr.previous().getRSS())<SensorManager.GRAVITY_EARTH)) {}

		KVector leapEnd=itr.next();

		//The Free Fall Interval is the difference between the landing start and leap end.
		long FFI=landingStart-(long)(leapEnd.getTimeStamp()*MICRO_TO_MILLI);
		//totalAcc will keep a running total of all the RSS values for the vectors in the FFI
		double totalAcc=leapEnd.getRSS();
		//accCount will keep track of the number of vectors in the free fall interval.
		int accCount=1;
		KVector currVector;
		
		while((itr.hasNext())&&(((currVector=itr.next()).getTimeStamp()*MICRO_TO_MILLI)<=landingStart))
		{
		   totalAcc+=currVector.getRSS();
		   accCount++;
		}

		//The Free Fall Average Acceleration Magnitude equals the total of the RSS values of all vectors in the FFI
		//divided by the number of vectors in the free fall interval.
		double FFAAM=totalAcc/accCount;

		if((FFI>(long)FFI_MAX)&&(FFAAM<FFAAM_MIN))
		{
		   ADLTypeC=true;
		   if (debug)
		   {
		      Log.i(tag, "FFI = " + FFI +"::FFAAM = "+FFAAM+"::Time detected: " + curr_vector.getTimeStamp());
		      logMsg+="Calculated FFI and FFAAM. Detected a class C ADL. FFI = " + FFI +" FFAAM = "+FFAAM+" Time detected: " + curr_vector.getTimeStamp()+"\n";
		   }
		}
		else
		{
		   ADLTypeC=false;
		   if (debug)
		   {
		      Log.i(tag, "FFI = " + FFI +"::FFAAM = "+FFAAM+"::Time calculated: " + curr_vector.getTimeStamp());
		      logMsg+="Calculated FFI and FFAAM. Did not detect a class C ADL. FFI = " + FFI +" FFAAM = "+FFAAM+" Time calculated: " + curr_vector.getTimeStamp()+"\n";
		   }
		}
	}
	
	/**
	 * This function counts the inactivity period by using the scalar product between 2 consecutive readings to 
	 * measure inactivity. If in at least 17 consecutive acceleration readings, the scalar product is lower 
	 * than 3 degrees (depicting none or minuscule change between the readings), then a period of inactivity is detected. 
	 * 
	 * This is set to .51 seconds. 
	 */
	private void determineInactivityPeriod()
	{
		inactivityCounter1++;
		Log.i(tag, "determineInactivityPeriod called. Current Count: " + inactivityCounter2 + "::Total Time: " + ((float)inactivity_timestamp/1000000) + " seconds");
		
		if ((curr_ori < 3) && ( prev_ori < 3))
		{
			inactivityCounter2++;
			if(inactivityCounter2>=17)
			{
				inactivity_timestamp += curr_vector.getTimeStamp() - prev_vector.getTimeStamp();
			
				inactivityDetected = true;
				//this is to reset the LFT value because it is possible for the LFT to be exceeded, then inactivity, then UFT exceeded.
				LFT_exceeded = false;
				
				if(debug)
				{
					Log.i(tag, "Inactivity Period Detected! Current Count: " + inactivityCounter2 + "::Timestamp is: " + curr_vector.getTimeStamp());
					logMsg+="Inactivity Period Detected! Current Count: " + inactivityCounter2 + "::Timestamp: " + curr_vector.getTimeStamp();
				}
			}
		}
	
		else
		{
			inactivityCounter2=0;
			Log.i(tag, "Inactivity Period reset. Current Count: " + inactivityCounter2);
		}
	}
	
	
	private void storeData(){
		
		
				try 
				{ 
					File sdCard = Environment.getExternalStorageDirectory();
			       	File directory = new File (sdCard.getAbsolutePath() + "/MyFiles");
			       	directory.mkdirs();
			       	
			       	File RSS = new File(directory, "RSS_Gs.txt"); 
			        FileOutputStream fOutRSS = new FileOutputStream(RSS, true);
				    OutputStreamWriter oswRSS = new OutputStreamWriter(fOutRSS); 

				    // Write the string to the file.
				    oswRSS.write(curr_vector.getRSS() / SensorManager.GRAVITY_EARTH+"\n");
				    oswRSS.flush();
				    oswRSS.close();
				    
				    File timestamp = new File(directory, "Timestamps.txt"); 
			       	FileOutputStream fOutTimestamp = new FileOutputStream(timestamp, true);
				    OutputStreamWriter oswTimestamp = new OutputStreamWriter(fOutTimestamp); 
				    oswTimestamp.write(curr_vector.getTimeStamp()+"\n");
				    oswTimestamp.flush();
				    oswTimestamp.close();
				    
				    File acceleration = new File(directory, "acceleration_data.txt"); 
			       	FileOutputStream fOutAcceleration = new FileOutputStream(acceleration, true);
				    OutputStreamWriter oswAcceleration = new OutputStreamWriter(fOutAcceleration); 
					// Write the string to the file.
				    oswAcceleration.write(curr_vector.getX()+","+curr_vector.getY()+","+curr_vector.getZ()+"\n");
				    oswAcceleration.flush();
				    oswAcceleration.close();
				    
				    File messageDirectory = new File (sdCard.getAbsolutePath() + "/MyFiles"+"/FallDetectionMessages");
			       	messageDirectory.mkdirs();
			       	File messages = new File(messageDirectory, "KVector_data.txt"); 
			       	FileOutputStream fOutMessages = new FileOutputStream(messages);
				    OutputStreamWriter oswMessages = new OutputStreamWriter(fOutMessages); 
					// Write the string to the file.
				    oswMessages.write(SensorService.getMsg()+"\n");
				    oswMessages.flush();
				    oswMessages.close();				    
				   				     
				} 
				catch (IOException ioe) {ioe.printStackTrace();}
				
	}
	
	public static void startTesting(){
		testing=true;
	}
	
	public static void stopTesting(){
		testing=false;
	}
	
	
	public static String getMsg()
	{
		return logMsg;		
	}
	
	public static void clearMsg()
	{
		logMsg="Service 1\n";
	}
	
	@Override
	public IBinder onBind(Intent arg0) { return null; }
	
	
	
	public class MyBinder extends Binder 
	{
		public SensorService getService() 
		{
			return SensorService.this;
		}
	}	
}














