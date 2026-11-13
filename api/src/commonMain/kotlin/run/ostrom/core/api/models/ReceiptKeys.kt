// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * A page of receipts (`GET /api/v1/receipts`).
 *
 * @property items The receipts of the page.
 * @property next The cursor of the following page, absent on the last one.
 */
@Serializable
public data class ReceiptPage(
    val items: List<Receipt>,
    val next: String? = null,
)

/**
 * The public keys that sign the instance's receipts (`GET /api/v1/receipts/keys`).
 *
 * @property keys The keys.
 */
@Serializable
public data class ReceiptKeyList(
    val keys: List<PublicKey>,
)

/**
 * A public key of the instance.
 *
 * @property kid Key id.
 * @property alg Its algorithm.
 * @property publicKeyPem The key, in PEM.
 * @property rotation How the key it replaces handed it over; absent on a key handed over by nobody.
 */
@Serializable
public data class PublicKey(
    val kid: String,
    val alg: String,
    val publicKeyPem: String,
    val rotation: KeyRotation? = null,
)

/**
 * How a receipt key was handed over by the key it replaces. A reader that pinned the previous key trusts this one
 * only if [signature] verifies.
 *
 * @property kid The id of the key this key replaces.
 * @property signature The replaced key's Ed25519 signature, base64, over `ostrom-receipt-key-rotation/v1\n` followed
 *   by the lowercase hex of this key's 32 raw bytes.
 */
@Serializable
public data class KeyRotation(
    val kid: String,
    val signature: String,
)
