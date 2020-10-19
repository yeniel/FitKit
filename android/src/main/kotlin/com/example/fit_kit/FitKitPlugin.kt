package com.example.fit_kit

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessActivities
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.FitnessStatusCodes
import com.google.android.gms.fitness.data.*
import com.google.android.gms.fitness.request.DataReadRequest
import com.google.android.gms.fitness.request.SessionInsertRequest
import com.google.android.gms.fitness.request.SessionReadRequest
import com.google.android.gms.fitness.result.DataReadResponse
import com.google.android.gms.fitness.result.SessionReadResponse
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.util.concurrent.TimeUnit


class FitKitPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    private var context: Context? = null
    private var activity : Activity? = null
    private var channel: MethodChannel? = null

    private var result: Result ? = null
    private var readRequest: ReadRequest<*> ? = null
    private var writeRequest: WriteRequest<Type.Activity> ? = null

    interface OAuthPermissionsListener {
        fun onOAuthPermissionsResult(resultCode: Int)
    }

    private val oAuthPermissionListeners = mutableListOf<OAuthPermissionsListener>()

    companion object {
        private const val TAG = "FitKit"
        private const val TAG_UNSUPPORTED = "unsupported"
        private const val GOOGLE_FIT_REQUEST_CODE = 8008
        private const val OAUTH_WRITE_REQUEST_CODE = 6006
        private const val OAUTH_READ_REQUEST_CODE = 7007

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val plugin = FitKitPlugin()
            plugin.context = registrar.context()
            plugin.activity = registrar.activity()

            registrar.addActivityResultListener { requestCode, resultCode, _ ->
                if (requestCode == GOOGLE_FIT_REQUEST_CODE) {
                    plugin.oAuthPermissionListeners.toList()
                            .forEach { it.onOAuthPermissionsResult(resultCode) }
                    return@addActivityResultListener true
                }
                return@addActivityResultListener false
            }

            val channel = MethodChannel(registrar.messenger(), "fit_kit")
            channel.setMethodCallHandler(plugin)
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        this.context = binding.applicationContext

        this.channel = MethodChannel(binding.binaryMessenger, "fit_kit")
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activity = binding.activity

        binding.addActivityResultListener { requestCode, resultCode, _ ->
            when (requestCode) {
                GOOGLE_FIT_REQUEST_CODE -> {
                    oAuthPermissionListeners.toList()
                            .forEach { it.onOAuthPermissionsResult(resultCode) }
                    return@addActivityResultListener true
                }
                OAUTH_WRITE_REQUEST_CODE -> {
                    this.result?.let { res ->
                        this.writeRequest?.let { req ->
                            writeSession(req, res)
                        }
                    }
                    return@addActivityResultListener true

                }
                OAUTH_READ_REQUEST_CODE -> {
                    this.result?.let { res ->
                        this.readRequest?.let { req ->
                            read(req, res)
                        }
                    }
                    return@addActivityResultListener true
                }
                else -> return@addActivityResultListener false
            }
        }
    }

    override fun onDetachedFromActivity() {
        this.activity = null;
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        try {
            when (call.method) {
                "hasPermissions" -> {
                    val request = PermissionsRequest.fromCall(call)
                    hasPermissions(request, result)
                }
                "requestPermissions" -> {
                    val request = PermissionsRequest.fromCall(call)
                    requestPermissions(request, result)
                }
                "revokePermissions" -> revokePermissions(result)
                "read" -> {
                    val request = ReadRequest.fromCall(call)
                    read(request, result)
                }
                "write" -> {
                    val request = WriteRequest.fromCall(call)
                    write(request, result)
                }
                else -> result.notImplemented()
            }
        } catch (e: UnsupportedException) {
            result.error(TAG_UNSUPPORTED, e.message, null)
        } catch (e: Throwable) {
            result.error(TAG, e.message, null)
        }
    }

    private fun hasPermissions(request: PermissionsRequest, result: Result) {
        val options = FitnessOptions.builder()
                .addDataTypes(request.types.map { it.dataType })
                .build()

        if (hasOAuthPermission(options)) {
            result.success(true)
        } else {
            result.success(false)
        }
    }

    private fun requestPermissions(request: PermissionsRequest, result: Result) {
        val options = FitnessOptions.builder()
                .addDataTypes(request.types.map { it.dataType })
                .build()

        requestOAuthPermissions(options, {
            result.success(true)
        }, {
            result.success(false)
        })
    }

    /**
     * let's wait for some answers
     * https://github.com/android/fit-samples/issues/28#issuecomment-557865949
     */
    private fun revokePermissions(result: Result) {
        val fitnessOptions = FitnessOptions.builder()
                .build()

        if (!hasOAuthPermission(fitnessOptions)) {
            result.success(null)
            return
        }

        context?.let {ctx ->
            val account = GoogleSignIn.getLastSignedInAccount(ctx)
            account?.let { acc ->
                Fitness.getConfigClient(ctx, acc)
                        .disableFit()
                        .continueWithTask {
                            val signInOptions = GoogleSignInOptions.Builder()
                                    .addExtension(fitnessOptions)
                                    .build()
                            GoogleSignIn.getClient(ctx, signInOptions)
                                    .revokeAccess()
                        }
                        .addOnSuccessListener { result.success(null) }
                        .addOnFailureListener { e ->
                            if (!hasOAuthPermission(fitnessOptions)) {
                                result.success(null)
                            } else {
                                result.error(TAG, e.message, null)
                            }
                        }
            }
        }
    }

    private fun read(request: ReadRequest<*>, result: Result) {
        val options = FitnessOptions.builder()
                .addDataType(request.type.dataType)
                .build()

        requestOAuthPermissions(options, {
            when (request) {
                is ReadRequest.Sample -> readSample(request, result)
                is ReadRequest.Activity -> readSession(request, result)
            }
        }, {
            result.error(TAG, "User denied permission access", null)
        })
    }

    private fun write(request: WriteRequest<Type.Activity>, result: Result) {
        val options = FitnessOptions.builder()
                .addDataType(request.type.dataType)
                .build()

        requestOAuthPermissions(options, {
            writeSession(request, result)
        }, {
            result.error(TAG, "User denied permission access", null)
        })
    }

    private fun requestOAuthPermissions(fitnessOptions: FitnessOptions, onSuccess: () -> Unit, onError: () -> Unit) {
        if (hasOAuthPermission(fitnessOptions)) {
            onSuccess()
            return
        } else {
            oAuthPermissionListeners.add(object : OAuthPermissionsListener {
                override fun onOAuthPermissionsResult(resultCode: Int) {
                    oAuthPermissionListeners.remove(this)

                    if (resultCode == Activity.RESULT_OK) {
                        onSuccess()
                    } else {
                        onError()
                    }
                }
            })

            activity?.let {act ->
                context?.let {ctx ->
                    GoogleSignIn.requestPermissions(
                            act,
                            GOOGLE_FIT_REQUEST_CODE,
                            GoogleSignIn.getLastSignedInAccount(ctx),
                            fitnessOptions)
                }

            }

        }
    }

    private fun hasOAuthPermission(fitnessOptions: FitnessOptions): Boolean {
        context?.let {ctx ->
            return GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(ctx), fitnessOptions)
        }

        return false
    }

    private fun readSample(request: ReadRequest<Type.Sample>, result: Result) {
        Log.d(TAG, "readSample: ${request.type}")

        val readRequest = DataReadRequest.Builder()
                .read(request.type.dataType)
                .also { builder ->
                    when (request.limit != null) {
                        true -> builder.setLimit(request.limit)
                        else -> builder.bucketByTime(1, TimeUnit.DAYS)
                    }
                }
                .setTimeRange(request.dateFrom.time, request.dateTo.time, TimeUnit.MILLISECONDS)
                .enableServerQueries()
                .build()

        context?.let { ctx ->
            val account = GoogleSignIn.getLastSignedInAccount(ctx)
            account?.let { acc ->
                Fitness.getHistoryClient(ctx, acc)
                        .readData(readRequest)
                        .addOnSuccessListener { response -> onSuccess(response, result) }
                        .addOnFailureListener {e ->
                            if (e is ResolvableApiException) {
                                if (e.statusCode == FitnessStatusCodes.NEEDS_OAUTH_PERMISSIONS) {
                                    this.result = result
                                    this.readRequest = request
                                    e.startResolutionForResult(activity, OAUTH_READ_REQUEST_CODE)
                                }
                            } else {
                                result.error(TAG, e.message, null)
                            }
                        }
                        .addOnCanceledListener { result.error(TAG, "GoogleFit Cancelled", null) }
            }
        }
    }

    private fun onSuccess(response: DataReadResponse, result: Result) {
        (response.dataSets + response.buckets.flatMap { it.dataSets })
                .filterNot { it.isEmpty }
                .flatMap { it.dataPoints }
                .map(::dataPointToMap)
                .let(result::success)
    }

    @Suppress("IMPLICIT_CAST_TO_ANY")
    private fun dataPointToMap(dataPoint: DataPoint): Map<String, Any> {
        val field = dataPoint.dataType.fields.first()
        val source = dataPoint.originalDataSource.streamName

        return mapOf(
                "value" to dataPoint.getValue(field).let { value ->
                    when (value.format) {
                        Field.FORMAT_FLOAT -> value.asFloat()
                        Field.FORMAT_INT32 -> value.asInt()
                        else -> TODO("for future fields")
                    }
                },
                "date_from" to dataPoint.getStartTime(TimeUnit.MILLISECONDS),
                "date_to" to dataPoint.getEndTime(TimeUnit.MILLISECONDS),
                "source" to source,
                "user_entered" to (source == "user_input")
        )
    }

    private fun readSession(request: ReadRequest<Type.Activity>, result: Result) {
        Log.d(TAG, "readSession: ${request.type.activity}")

        val readRequest = SessionReadRequest.Builder()
                .read(request.type.dataType)
                .setTimeInterval(request.dateFrom.time, request.dateTo.time, TimeUnit.MILLISECONDS)
                .readSessionsFromAllApps()
                .enableServerQueries()
                .build()

        context?.let { ctx ->
            val account = GoogleSignIn.getLastSignedInAccount(ctx)
            account?.let { acc ->
                Fitness.getSessionsClient(ctx, acc)
                        .readSession(readRequest)
                        .addOnSuccessListener { response -> onSuccess(request, response, result) }
                        .addOnFailureListener { e ->
                            if (e is ResolvableApiException) {
                                if (e.statusCode == FitnessStatusCodes.NEEDS_OAUTH_PERMISSIONS) {
                                    this.result = result
                                    this.readRequest = request
                                    e.startResolutionForResult(activity, OAUTH_READ_REQUEST_CODE)
                                }
                            } else {
                                result.error(TAG, e.message, null)
                            }
                        }
                        .addOnCanceledListener { result.error(TAG, "GoogleFit Cancelled", null) }
            }
        }
    }

    private fun writeSession(request: WriteRequest<Type.Activity>, result: Result) {
        Log.d(TAG, "writeSession: ${request.type.activity}")

        val session = Session.Builder()
                .setName(request.name)
                .setDescription(request.description)
                .setIdentifier(request.dateFrom.time.toString())
                .setActivity(FitnessActivities.MEDITATION)
                .setStartTime(request.dateFrom.time, TimeUnit.MILLISECONDS)
                .setEndTime(request.dateTo.time, TimeUnit.MILLISECONDS)
                .build()

        // Build a session insert request
        val insertRequest = SessionInsertRequest.Builder()
                .setSession(session)
                //.addDataSet(speedDataSet)
                //.addDataSet(activitySegments)
                .build()


        context?.let { ctx ->
            val account = GoogleSignIn.getLastSignedInAccount(ctx)

            Log.i(TAG, "Inserting the session in the History API")

            account?.let { acc ->
                Fitness.getSessionsClient(ctx, acc)
                        .insertSession(insertRequest)
                        .addOnSuccessListener { result.success(true) }
                        .addOnFailureListener { e ->
                            if (e is ResolvableApiException) {
                                if (e.statusCode == FitnessStatusCodes.NEEDS_OAUTH_PERMISSIONS) {
                                    this.result = result
                                    this.writeRequest = request
                                    e.startResolutionForResult(activity, OAUTH_WRITE_REQUEST_CODE)
                                }
                            } else {
                                result.error(TAG, e.message, null)
                            }
                        }
                        .addOnCanceledListener { result.error(TAG, "GoogleFit Cancelled", null) }
            }
        }
    }

    private fun onSuccess(request: ReadRequest<Type.Activity>, response: SessionReadResponse, result: Result) {
        response.sessions.filter { request.type.activity == it.activity }
                .let { list ->
                    when (request.limit != null) {
                        true -> list.takeLast(request.limit)
                        else -> list
                    }
                }
                .map { session -> sessionToMap(session, response.getDataSet(session)) }
                .let(result::success)
    }

    private fun sessionToMap(session: Session, dataSets: List<DataSet>): Map<String, Any> {
        // from all data points find the top used streamName
        val source = dataSets.asSequence()
                .filterNot { it.isEmpty }
                .flatMap { it.dataPoints.asSequence() }
                .filterNot { it.originalDataSource.streamName.isNullOrEmpty() }
                .groupingBy { it.originalDataSource.streamName }
                .eachCount()
                .maxBy { it.value }
                ?.key ?: session.name ?: ""

        return mapOf(
                "value" to session.getValue(),
                "date_from" to session.getStartTime(TimeUnit.MILLISECONDS),
                "date_to" to session.getEndTime(TimeUnit.MILLISECONDS),
                "source" to source,
                "user_entered" to (source == "user_input")
        )
    }
}
