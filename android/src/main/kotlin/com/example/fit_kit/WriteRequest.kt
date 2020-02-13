package com.example.fit_kit

import io.flutter.plugin.common.MethodCall
import java.util.*

abstract class WriteRequest<T : Type> private constructor(
        val type: T,
        val dateFrom: Date,
        val dateTo: Date,
        val name: String,
        val description: String,
) {

    class Sample(type: Type.Sample, dateFrom: Date, dateTo: Date, name: String, description: String)
        : WriteRequest<Type.Sample>(type, dateFrom, dateTo, name, description)

    class Activity(type: Type.Activity, dateFrom: Date, dateTo: Date, name: String, description: String)
        : WriteRequest<Type.Activity>(type, dateFrom, dateTo, name, description)

    companion object {
        @Throws
        fun fromCall(call: MethodCall): WriteRequest<*> {
            val type = call.argument<String>("type")?.fromDartType()
                    ?: throw Exception("type is not defined")
            val dateFrom = safeLong(call, "date_from")?.let { Date(it) }
                    ?: throw Exception("date_from is not defined")
            val dateTo = safeLong(call, "date_to")?.let { Date(it) }
                    ?: throw Exception("date_to is not defined")
            val name = call.argument("name") ?? ""
            val description = call.argument("description") ?? ""

            return when (type) {
                is Type.Sample -> Sample(type, dateFrom, dateTo, name, description)
                is Type.Activity -> Activity(type, dateFrom, dateTo, name, description)
            }
        }

        /**
         *  Dart | Android
         *  int	   java.lang.Integer
         *  int    java.lang.Long
         */
        private fun safeLong(call: MethodCall, key: String): Long? {
            val value: Any? = call.argument(key)
            return when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            }
        }
    }
}