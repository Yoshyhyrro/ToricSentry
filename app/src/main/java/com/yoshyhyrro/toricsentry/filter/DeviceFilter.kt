package com.yoshyhyrro.toricsentry.filter

/**
 * BLE スキャン結果のフィルタリング層。
 * 「安全な（無視すべき）機器かどうか」を判定する責務のみを持つ。
 *
 * ## フィルタリング戦略
 * 1. **MACアドレス ホワイトリスト**: 事前登録した正規の機器は無条件に通過させない。
 * 2. **ベンダー ID（Company Identifier）マッチング**: 市販の正規POS端末に使われる
 *    Bluetooth SIG 登録済みベンダーコードを持つ機器は安全とみなして除外する。
 *
 * フィルターを「通過した＝不審」なので、[shouldIgnore] が false を返した場合のみ
 * 検知エンジンへ渡すこと。
 */
class DeviceFilter(
    /** 事前登録する安全な機器の MAC アドレス一覧（大文字・コロン区切り形式） */
    private val trustedMacAddresses: MutableSet<String> = mutableSetOf(),
    /**
     * 安全とみなすベンダー ID（Bluetooth SIG Company Identifier）の一覧。
     * 例: 0x0075 = Apple, 0x00E0 = Google, 0x02E5 = Anthem Payment solutions
     * https://www.bluetooth.com/specifications/assigned-numbers/
     */
    private val trustedVendorIds: Set<Int> = DEFAULT_TRUSTED_VENDOR_IDS
) {

    companion object {
        /**
         * 市販決済端末・POS機器でよく使われるベンダー ID の既定セット。
         * 実運用に応じて差し替え・追加すること。
         */
        val DEFAULT_TRUSTED_VENDOR_IDS: Set<Int> = setOf(
            0x0006, // Microsoft Corporation
            0x0075, // Apple Inc.
            0x004C, // Apple Inc. (iBeacon / iPhone アドバタイズ)
            0x00E0, // Google LLC
            0x012D, // Sony Corporation (PS4/PS5 コントローラー等)
            0x02E5, // Anthem Inc. (Ingenico 系)
            0x0499, // Ruuvi Innovations (一般センサー)
            0x059E, // Square, Inc.
            0x1903, // Bose Corporation
        )
    }

    /** [macAddress] を安全なデバイスとしてホワイトリストに追加する */
    fun addTrustedMac(macAddress: String) {
        trustedMacAddresses.add(macAddress.uppercase())
    }

    /** ホワイトリストから [macAddress] を削除する */
    fun removeTrustedMac(macAddress: String) {
        trustedMacAddresses.remove(macAddress.uppercase())
    }

    /**
     * この機器を**無視すべきか**を返す。
     *
     * @param macAddress  対象機器の MAC アドレス（大文字・コロン区切り）
     * @param vendorId    アドバタイズパケットの Manufacturer Specific Data から取得した
     *                    Company Identifier（取得できない場合は null）
     * @return `true` のとき無視（検知エンジンへ渡さない）、`false` のとき要検査
     */
    fun shouldIgnore(macAddress: String, vendorId: Int? = null): Boolean {
        // 1. MAC ホワイトリスト一致
        if (macAddress.uppercase() in trustedMacAddresses) return true
        // 2. ベンダー ID が既知の安全なメーカーに一致
        if (vendorId != null && vendorId in trustedVendorIds) return true
        return false
    }

    /** 現在のホワイトリスト（MACアドレス一覧）を返す（読み取り専用） */
    fun trustedMacs(): Set<String> = trustedMacAddresses.toSet()
}
