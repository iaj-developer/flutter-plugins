package cachet.plugins.health;

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.*
import com.google.android.gms.fitness.request.DataReadRequest
import com.google.android.gms.fitness.result.DataReadResponse
import com.google.android.gms.tasks.Tasks
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.*
import io.flutter.plugin.common.PluginRegistry
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MethodCallHandlerImpl(private val activity: Activity?) : MethodCallHandler, Result, PluginRegistry.ActivityResultListener {

  private lateinit var result: Result

  private var BODY_FAT_PERCENTAGE = "BODY_FAT_PERCENTAGE"
  private var HEIGHT = "HEIGHT"
  private var WEIGHT = "WEIGHT"
  private var STEPS = "STEPS"
  private var ACTIVE_ENERGY_BURNED = "ACTIVE_ENERGY_BURNED"
  private var HEART_RATE = "HEART_RATE"
  private var BODY_TEMPERATURE = "BODY_TEMPERATURE"
  private var BLOOD_PRESSURE_SYSTOLIC = "BLOOD_PRESSURE_SYSTOLIC"
  private var BLOOD_PRESSURE_DIASTOLIC = "BLOOD_PRESSURE_DIASTOLIC"
  private var BLOOD_OXYGEN = "BLOOD_OXYGEN"
  private var BLOOD_GLUCOSE = "BLOOD_GLUCOSE"
  private var MOVE_MINUTES = "MOVE_MINUTES"
  private var DISTANCE_DELTA = "DISTANCE_DELTA"

  override fun onMethodCall(call: MethodCall, result: Result) {
    this.result = result
    when (call.method) {
      "hasAuthorization" -> hasAuthorization(call, result)
      "requestAuthorization" -> requestAuthorization(call, result)
      "getData" -> getData(call, result)
      else -> result.notImplemented()
    }
  }

  /// Called when the "getHealthDataByType" is invoked from Flutter
  private fun getData(call: MethodCall, result: Result) {
    if (activity == null) {
      result.success(null)
      return
    }

    val type = call.argument<String>("dataTypeKey")!!
    val startTime = call.argument<Long>("startDate")!!
    val endTime = call.argument<Long>("endDate")!!
    val limit = call.argument<Int>("limit")!!

    // Look up data type and unit for the type key
    val dataType = keyToHealthDataType(type)
    val unit = getUnit(type)

    /// Start a new thread for doing a GoogleFit data lookup
    thread {
      try {
        val fitnessOptions = FitnessOptions.builder().addDataType(dataType).build()
        val googleSignInAccount = GoogleSignIn.getAccountForExtension(activity.applicationContext, fitnessOptions)

        val response = Fitness.getHistoryClient(activity.applicationContext, googleSignInAccount)
                .readData(DataReadRequest.Builder()
                        .read(dataType)
                        .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                        .also { builder ->
                          when (limit != 0) {
                            true -> builder.setLimit(limit)
                          }
                        }
                        .build())

        /// Fetch all data points for the specified DataType
        val dataPoints = Tasks.await<DataReadResponse>(response).getDataSet(dataType)

        /// For each data point, extract the contents and send them to Flutter, along with date and unit.
        val healthData = dataPoints.dataPoints.mapIndexed { _, dataPoint ->
          return@mapIndexed hashMapOf(
                  "value" to getHealthDataValue(dataPoint, unit),
                  "date_from" to dataPoint.getStartTime(TimeUnit.MILLISECONDS),
                  "date_to" to dataPoint.getEndTime(TimeUnit.MILLISECONDS),
                  "unit" to unit.toString()
          )

        }
        activity.runOnUiThread { result.success(healthData) }
      } catch (e3: Exception) {
        activity.runOnUiThread { result.success(null) }
      }
    }
  }

  /// Called when the "requestAuthorization" is invoked from Flutter
  private fun requestAuthorization(call: MethodCall, result: Result) {
    if (activity == null) {
      result.success(false)
      return
    }

    val optionsToRegister = callToHealthTypes(call)

    val isGranted = GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(activity), optionsToRegister)

    /// Not granted? Ask for permission
    if (!isGranted) {
      GoogleSignIn.requestPermissions(
              activity,
              GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
              GoogleSignIn.getLastSignedInAccount(activity),
              optionsToRegister)
    }
    /// Permission already granted
    else {
      result.success(true)
    }
  }

  /// Called when the "hasAuthorization" is invoked from Flutter
  private fun hasAuthorization(call: MethodCall, result: Result) {
    if (activity == null) {
      result.success(false)
      return
    }

    val optionsToRegister = callToHealthTypes(call)

    val isGranted = GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(activity), optionsToRegister)

    result.success(isGranted)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode == GOOGLE_FIT_PERMISSIONS_REQUEST_CODE) {
      if (resultCode == Activity.RESULT_OK) {
        Log.d("FLUTTER_HEALTH", "Access Granted!")
        result.success(true)
      } else if (resultCode == Activity.RESULT_CANCELED) {
        Log.d("FLUTTER_HEALTH", "Access Denied!")
        result.success(false);
      }
    }
    return false
  }

  override fun success(p0: Any?) {
    result.success(p0)
  }

  override fun notImplemented() {
    result.notImplemented()
  }

  override fun error(
          errorCode: String, errorMessage: String?, errorDetails: Any?) {
    result.error(errorCode, errorMessage, errorDetails)
  }

  private fun keyToHealthDataType(type: String): DataType {
    return when (type) {
      BODY_FAT_PERCENTAGE -> DataType.TYPE_BODY_FAT_PERCENTAGE
      HEIGHT -> DataType.TYPE_HEIGHT
      WEIGHT -> DataType.TYPE_WEIGHT
      STEPS -> DataType.TYPE_STEP_COUNT_DELTA
      ACTIVE_ENERGY_BURNED -> DataType.TYPE_CALORIES_EXPENDED
      HEART_RATE -> DataType.TYPE_HEART_RATE_BPM
      BODY_TEMPERATURE -> HealthDataTypes.TYPE_BODY_TEMPERATURE
      BLOOD_PRESSURE_SYSTOLIC -> HealthDataTypes.TYPE_BLOOD_PRESSURE
      BLOOD_PRESSURE_DIASTOLIC -> HealthDataTypes.TYPE_BLOOD_PRESSURE
      BLOOD_OXYGEN -> HealthDataTypes.TYPE_OXYGEN_SATURATION
      BLOOD_GLUCOSE -> HealthDataTypes.TYPE_BLOOD_GLUCOSE
      MOVE_MINUTES -> DataType.TYPE_MOVE_MINUTES
      DISTANCE_DELTA -> DataType.TYPE_DISTANCE_DELTA
            else -> DataType.TYPE_STEP_COUNT_DELTA
    }
  }

  private fun getUnit(type: String): Field {
    return when (type) {
      BODY_FAT_PERCENTAGE -> Field.FIELD_PERCENTAGE
      HEIGHT -> Field.FIELD_HEIGHT
      WEIGHT -> Field.FIELD_WEIGHT
      STEPS -> Field.FIELD_STEPS
      ACTIVE_ENERGY_BURNED -> Field.FIELD_CALORIES
      HEART_RATE -> Field.FIELD_BPM
      BODY_TEMPERATURE -> HealthFields.FIELD_BODY_TEMPERATURE
      BLOOD_PRESSURE_SYSTOLIC -> HealthFields.FIELD_BLOOD_PRESSURE_SYSTOLIC
      BLOOD_PRESSURE_DIASTOLIC -> HealthFields.FIELD_BLOOD_PRESSURE_DIASTOLIC
      BLOOD_OXYGEN -> HealthFields.FIELD_OXYGEN_SATURATION
      BLOOD_GLUCOSE -> HealthFields.FIELD_BLOOD_GLUCOSE_LEVEL
      MOVE_MINUTES -> Field.FIELD_DURATION
      DISTANCE_DELTA -> Field.FIELD_DISTANCE
            else -> Field.FIELD_PERCENTAGE
    }
  }

  /// Extracts the (numeric) value from a Health Data Point
  private fun getHealthDataValue(dataPoint: DataPoint, unit: Field): Any {
    return try {
      dataPoint.getValue(unit).asFloat()
    } catch (e1: Exception) {
      try {
        dataPoint.getValue(unit).asInt()
      } catch (e2: Exception) {
        try {
          dataPoint.getValue(unit).asString()
        } catch (e3: Exception) {
          Log.e("FLUTTER_HEALTH::ERROR", e3.toString())
        }
      }
    }
  }

  private fun callToHealthTypes(call: MethodCall): FitnessOptions {
    val typesBuilder = FitnessOptions.builder()
    val args = call.arguments as HashMap<*, *>
    val types = args["types"] as ArrayList<*>
    for (typeKey in types) {
      if (typeKey !is String) continue
              typesBuilder.addDataType(keyToHealthDataType(typeKey), FitnessOptions.ACCESS_READ)
    }
    return typesBuilder.build()
  }
}
