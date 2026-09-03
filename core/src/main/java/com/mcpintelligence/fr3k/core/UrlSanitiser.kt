package com.mcpintelligence.fr3k.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Deterministic URL sanitisation engine (§27).
 *
 * Removes common tracking parameters. Preserves parameters that look
 * functionally required. Per-domain rules can be added via [DomainRule].
 */
class UrlSanitiser {

    private val trackers = setOf(
        // Universal
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "utm_name",
        "gclid", "gclsrc", "gbraid", "wbraid", "dclid",
        "fbclid", "fb_action_ids", "fb_action_types", "fb_source", "fb_ref",
        "msclkid", "mc_cid", "mc_eid",
        "_ga", "_gl", "_hsenc", "_hsmi", "_hsfp", "hsCtaTracking",
        "igshid", "si", "feature",
        "yclid", "ref_src", "ref_url",
        "mkt_tok", "vero_id", "vero_conv",
        "trk", "trkCampaign",
        // Tracking referrer / share
        "ref", "source",
    )

    /** Per-domain override. */
    data class DomainRule(val host: Set<String>, val preserve: Set<String>, val stripExtra: Set<String>)

    private val domainRules = listOf(
        DomainRule(
            host = setOf("youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com"),
            preserve = setOf("v", "list", "index", "t", "start", "end"),
            stripExtra = setOf("feature", "si", "pp"),
        ),
        DomainRule(
            host = setOf("twitter.com", "x.com", "mobile.twitter.com"),
            preserve = setOf("lang"),
            stripExtra = setOf("s", "t"),
        ),
        DomainRule(
            host = setOf("amazon.com", "amazon.com.au", "amazon.co.uk"),
            preserve = setOf("tag", "ref", "ref_", "th", "smid"),
            stripExtra = setOf("ref_", "tag"),
        ),
    )

    data class SanitisedUrl(val original: String, val clean: String, val removed: List<String>) {
        val changed: Boolean get() = removed.isNotEmpty()
    }

    fun clean(input: String): SanitisedUrl {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return SanitisedUrl(trimmed, trimmed, emptyList())

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return SanitisedUrl(trimmed, trimmed, emptyList())

        val host = uri.host?.lowercase() ?: ""
        val rule = domainRules.firstOrNull { host in it.host }

        val preserve = (rule?.preserve ?: emptySet()) - rule?.stripExtra.orEmpty()
        val removeSet = if (rule != null) {
            trackers - preserve
        } else trackers

        val rawQuery = uri.rawQuery ?: return SanitisedUrl(trimmed, trimmed, emptyList())
        val kept = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<String>()
        rawQuery.split("&").forEach { part ->
            if (part.isBlank()) return@forEach
            val eq = part.indexOf('=')
            val rawKey = if (eq < 0) part else part.substring(0, eq)
            val rawValue = if (eq < 0) "" else part.substring(eq + 1)
            val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
            if (key in removeSet) {
                removed += key
            } else {
                val encoded = URLEncoder.encode(rawValue, "UTF-8")
                kept += key to rawValue
            }
        }
        val newQuery = if (kept.isEmpty()) null else kept.joinToString("&") { (k, v) ->
            if (v.isEmpty()) k else "$k=$v"
        }
        val clean = runCatching {
            val rebuilt = URI(
                uri.scheme, uri.rawUserInfo, uri.host, uri.port,
                uri.rawPath, newQuery, uri.rawFragment,
            )
            rebuilt.toASCIIString()
        }.getOrDefault(trimmed)

        return SanitisedUrl(trimmed, clean, removed)
    }
}