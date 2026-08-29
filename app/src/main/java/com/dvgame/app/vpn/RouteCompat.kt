package com.dvgame.app.vpn

import java.net.InetAddress

/** Keeps Android 13+ route creation type-safe while libbox exposes addresses as strings. */
internal fun IpPrefix(address: String, prefixLength: Int): android.net.IpPrefix =
    android.net.IpPrefix(InetAddress.getByName(address), prefixLength)
