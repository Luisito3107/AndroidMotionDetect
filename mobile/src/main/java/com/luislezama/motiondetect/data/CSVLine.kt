package com.luislezama.motiondetect.data

import kotlin.reflect.full.primaryConstructor

data class CSVLine(
    val timestamp: Long = (System.currentTimeMillis() / 1000),
    val action: Action,
    val sessionName: String,
    val sessionUserName: String,
    val sessionUserHand: HandOption,
    val samplesPerPacket: Int,
    val accX: Float,
    val accY: Float,
    val accZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float
) {
    companion object {
        fun fromCSVString(csvString: String): CSVLine {
            val values = csvString.split(",")
            val constructor = CSVLine::class.primaryConstructor!!
            val parameters = constructor.parameters

            if (values.size != parameters.size) {
                throw IllegalArgumentException("CSV string value count does not match the number of parameters.")
            }

            val mappedValues = parameters.mapIndexed { index, parameter ->
                val value = values[index]
                when (parameter.type.classifier) {
                    Long::class -> value.toLong()
                    Action::class -> Action.fromString(value)
                    String::class -> value
                    HandOption::class -> HandOption.fromString(value)
                    Int::class -> value.toInt()
                    Float::class -> value.toFloat()
                    else -> throw IllegalArgumentException("Unsupported parameter type: ${parameter.type}")
                }
            }
            return constructor.call(*mappedValues.toTypedArray())
        }
    }


    private fun toCSVString(): String {
        return this::class.primaryConstructor!!.parameters.joinToString(",") { parameter ->
            val property = this::class.members.find { it.name == parameter.name }
            property!!.call(this).toString()
        }
    }

    override fun toString(): String {
        return toCSVString()
    }
}