package com.hookah.platform.backend.support

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import java.util.Locale

object BookingAuditReferencePolicy {
    private val json = Json { ignoreUnknownKeys = false }
    private val referenceFamilies = listOf("thread", "ticket", "conversation")
    private val referenceSegments = listOf("ids", "refs", "id", "ref")

    @JvmStatic
    fun hasUnknownThreadReferenceKey(payloadJson: String?): Boolean {
        val root = payloadJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return false
        return hasUnknownThreadReferenceKey(root, depth = 0)
    }

    @JvmStatic
    fun countTopLevelTicketIds(payloadJson: String?): Int = inspectTopLevelObject(payloadJson)?.ticketIds?.size ?: -1

    @JvmStatic
    fun extractTopLevelTicketId(payloadJson: String?): Long? {
        val ticketIds = inspectTopLevelObject(payloadJson)?.ticketIds ?: return null
        return ticketIds.singleOrNull()?.exactJsonLongOrNull()
    }

    @JvmStatic
    fun remapTopLevelTicketId(
        payloadJson: String?,
        expectedTicketId: Long,
        replacementTicketId: Long,
    ): String {
        val inspected =
            requireNotNull(inspectTopLevelObject(payloadJson)) {
                "Audit payload must be a valid JSON object"
            }
        val ticketMember =
            inspected.ticketMembers.singleOrNull()
                ?: throw IllegalArgumentException("Audit payload must contain exactly one top-level ticketId")
        require(ticketMember.value.exactJsonLongOrNull() == expectedTicketId) {
            "Audit payload ticketId must be an exact JSON integer matching entity_id"
        }

        return buildString {
            append('{')
            inspected.members.forEachIndexed { index, member ->
                if (index > 0) append(',')
                append(JsonPrimitive(member.key))
                append(':')
                append(if (member === ticketMember) JsonPrimitive(replacementTicketId) else member.value)
            }
            append('}')
        }
    }

    private fun hasUnknownThreadReferenceKey(
        element: JsonElement,
        depth: Int,
    ): Boolean =
        when (element) {
            is JsonObject ->
                element.entries.any { (key, value) ->
                    val allowedCanonicalKey = depth == 0 && key == "ticketId"
                    (!allowedCanonicalKey && isPotentialThreadReferenceKey(key)) ||
                        hasUnknownThreadReferenceKey(value, depth + 1)
                }
            is JsonArray -> element.any { hasUnknownThreadReferenceKey(it, depth + 1) }
            else -> false
        }

    private fun isPotentialThreadReferenceKey(key: String): Boolean {
        val compact =
            Normalizer.normalize(key, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .filterNot { it == '_' || it == '-' || it == '.' || it.isWhitespace() }
        return referenceFamilies.any { family ->
            val familyIndex = compact.indexOf(family)
            familyIndex >= 0 &&
                referenceSegments.any { segment ->
                    compact.indexOf(segment, familyIndex + family.length) >= 0
                }
        }
    }

    private fun inspectTopLevelObject(payloadJson: String?): InspectedObject? =
        payloadJson?.let {
            runCatching { json.decodeFromString(TopLevelObjectSerializer, it) }
                .getOrNull()
        }

    private fun JsonElement.exactJsonLongOrNull(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val token = primitive.content
        val digitStart = if (token.startsWith('-')) 1 else 0
        if (digitStart == token.length) return null
        if (token[digitStart] == '0' && digitStart + 1 != token.length) return null
        if (token.substring(digitStart).any { !it.isDigit() }) return null
        return token.toLongOrNull()
    }

    private data class JsonMember(
        val key: String,
        val value: JsonElement,
    )

    private data class InspectedObject(
        val members: List<JsonMember>,
    ) {
        val ticketMembers: List<JsonMember> = members.filter { it.key == "ticketId" }
        val ticketIds: List<JsonElement> = ticketMembers.map(JsonMember::value)
    }

    private object TopLevelObjectSerializer : KSerializer<InspectedObject> {
        private val mapSerializer = MapSerializer(String.serializer(), JsonElement.serializer())

        override val descriptor: SerialDescriptor = mapSerializer.descriptor

        override fun deserialize(decoder: Decoder): InspectedObject {
            val input = decoder.beginStructure(descriptor)
            val members = mutableListOf<JsonMember>()
            while (true) {
                val keyIndex = input.decodeElementIndex(descriptor)
                if (keyIndex == CompositeDecoder.DECODE_DONE) break
                require(keyIndex % 2 == 0) { "Expected a JSON object member key" }
                val key = input.decodeStringElement(descriptor, keyIndex)
                val valueIndex = input.decodeElementIndex(descriptor)
                require(valueIndex == keyIndex + 1) { "Expected a JSON object member value" }
                val value = input.decodeSerializableElement(descriptor, valueIndex, JsonElement.serializer())
                members += JsonMember(key, value)
            }
            input.endStructure(descriptor)
            return InspectedObject(members)
        }

        override fun serialize(
            encoder: Encoder,
            value: InspectedObject,
        ): Unit = error("TopLevelObjectSerializer is decode-only")
    }
}
