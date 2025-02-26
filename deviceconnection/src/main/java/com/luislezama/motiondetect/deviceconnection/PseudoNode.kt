package com.luislezama.motiondetect.deviceconnection

import com.google.android.gms.wearable.Node
import com.google.gson.Gson

class PseudoNode(val id: String, val displayName: String) {
    companion object {
        fun fromNode(node: Node): PseudoNode {
            return PseudoNode(node.id, node.displayName)
        }

        fun fromJson(json: String): PseudoNode? {
            try {
                return Gson().fromJson(json, PseudoNode::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
    }

    fun updateId(newId: String): PseudoNode {
        return PseudoNode(newId, displayName)
    }

    override fun toString(): String {
        return Gson().toJson(this)
    }
}