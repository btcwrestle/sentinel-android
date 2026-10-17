package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.Inputs
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.Xpub
import com.samourai.sentinel.data.prevOut
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Goldens verbatim from a live Dojo sync (testnet4, txids
 * bc822e9a… / bb8bea0f…). Two live-payload facts:
 *  1. IOs are tagged with xpub.m + path RELATIVE to the watched key
 *     (M/0/N) — absolute-path parsing cannot resolve accounts.
 *  2. Dojo returns ONE PARTIAL ROW PER WATCHED PUBKEY (inputs only,
 *     outputs only, …) — classification requires merging rows that
 *     share a bare txid before resolving accounts.
 */
class WhirlpoolLiveDojoGoldenTest {

    private val cid = "b28778f1-f392-40af-a1af-9db323408b6f"
    private val accountMap = mapOf(
        "vpub5Yig39b82QaiLtyWHh1xQUUD8fdQNwoNvGEHzjWNLZHhw41DcXPMP4AQZ2oj3RQWdVGE27STV11qxXxvb7HSWbuyF4zeEPHMY8GLk5Vc8nd" to 0L,
        "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe" to 2_147_483_645L,
        "vpub5Yig39bGN57gLvDjA3S9JjXE7KRueWiNRUGZUA5tzcFQ6m5EAziMCi9aFaySGvvrkCpTjqKyDJdsShHQRwyVnNMwupJhNkLYx69n4erkSyp" to 2_147_483_644L,
    )

        val p0_0 = Tx(
            hash = "bc822e9a3af8f56da3710ca66987b7cb7e1b704b1ef63d02ac875ee8161ba7fd-b28778f1-f392-40af-a1af-9db323408b6f",
            time = 0L, version = 1, locktime = 0, result = null, block_height = null,
            inputs = listOf(
            Inputs(vin = 0, sequence = 4294967295L, prev_out = prevOut(addr = "tb1q0z080ravd09jssfv7c2z8vd8m73xt2rm9r040e", txid = "7bb204d266aeece5ced11c03aee911d91bc9c55b9a464ff5cb3bf2b435104969", value = 56072483L, vout = 0, xpub = Xpub(m = "vpub5Yig39b82QaiLtyWHh1xQUUD8fdQNwoNvGEHzjWNLZHhw41DcXPMP4AQZ2oj3RQWdVGE27STV11qxXxvb7HSWbuyF4zeEPHMY8GLk5Vc8nd", path = "M/0/0"))),
            ),
            out = listOf(
            // no outputs (pubkey-scoped row),
            ),
        )

        val p0_1 = Tx(
            hash = "bc822e9a3af8f56da3710ca66987b7cb7e1b704b1ef63d02ac875ee8161ba7fd-b28778f1-f392-40af-a1af-9db323408b6f",
            time = 0L, version = 1, locktime = 0, result = null, block_height = null,
            inputs = listOf(
            // no inputs (pubkey-scoped row),
            ),
            out = listOf(
            Out(n = 2, value = 250605L, addr = "tb1qpv473ukfxmtucf4x7ptqs0q9efhlj3pc3tlur9", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/76")),
            Out(n = 3, value = 250605L, addr = "tb1qr8anyy85xntq3542ms4hk2kywqyyneekpc7g6v", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/78")),
            Out(n = 4, value = 250605L, addr = "tb1q9a7rme0x4kwn4sy0tgnjvq9dktplpy68djguky", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/94")),
            Out(n = 5, value = 250605L, addr = "tb1qge0gver567gxcnfs88y8j3hayfk0kuq3gfvzqy", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/81")),
            Out(n = 6, value = 250605L, addr = "tb1qfssdwdrgzcddaak4sl6zvcmr4an7mcsnky07eh", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/84")),
            Out(n = 7, value = 250605L, addr = "tb1q29qqk727u2w2d9k83vuzl5uwghh24zucshdv9c", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/83")),
            Out(n = 8, value = 250605L, addr = "tb1q2k784kakfcvms2nt0e5udtqpmtxeyryxuwrgnh", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/87")),
            Out(n = 9, value = 250605L, addr = "tb1q2h8kvu9k0pklllpphphrt2uyp9u4m9u86sj2cr", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/85")),
            Out(n = 10, value = 250605L, addr = "tb1q27dkdv4ukgc4pwd8fvdw97cj34z646wh9t3q3e", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/75")),
            Out(n = 11, value = 250605L, addr = "tb1q02euur6d6ecmxammxv7js5ny6xkax2glnp0lsw", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/86")),
            Out(n = 12, value = 250605L, addr = "tb1q0ejk4np7y2kgv22qlzns0wmnpnnre25xecrrg2", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/79")),
            Out(n = 13, value = 250605L, addr = "tb1qsrys3h069lvtcp383vzgmzj9fest9lj0rcjegt", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/91")),
            Out(n = 14, value = 250605L, addr = "tb1qj0ynlvlpecw9fpquxskq304zxk8at3ulmyfr9a", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/93")),
            Out(n = 15, value = 250605L, addr = "tb1qk980xf4t340zt95rpjjsauklwgqjzsecdqlnxm", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/92")),
            Out(n = 16, value = 250605L, addr = "tb1qcmspww34mc97pujjp7ccuhvzv8sevmjgrnhe8f", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/77")),
            Out(n = 17, value = 250605L, addr = "tb1qmt3wc7c4qxhcrf60uxxul9twg5u9kzqf58mwnu", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/89")),
            Out(n = 18, value = 250605L, addr = "tb1quz9h38f49e9u692560y4h3am64n0ky8zlge35d", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/80")),
            Out(n = 19, value = 250605L, addr = "tb1qu65ldlswutamwgxuv7uyvfhcu064e7dk4ha4vu", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/88")),
            Out(n = 20, value = 250605L, addr = "tb1q7anppg2lrng5fsjg7lqkgnu73uhg22tfzv6003", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/82")),
            Out(n = 21, value = 250605L, addr = "tb1qlhqefh92paf93mwew87cmxugderlw4vr5ckx98", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/90")),
            ),
        )

        val p0_2 = Tx(
            hash = "bc822e9a3af8f56da3710ca66987b7cb7e1b704b1ef63d02ac875ee8161ba7fd-b28778f1-f392-40af-a1af-9db323408b6f",
            time = 0L, version = 1, locktime = 0, result = null, block_height = null,
            inputs = listOf(
            // no inputs (pubkey-scoped row),
            ),
            out = listOf(
            Out(n = 22, value = 51046981L, addr = "tb1qjkajduwzrem4nth7ensshp4zzgkyhv38x6w25r", xpub = Xpub(m = "vpub5Yig39bGN57gLvDjA3S9JjXE7KRueWiNRUGZUA5tzcFQ6m5EAziMCi9aFaySGvvrkCpTjqKyDJdsShHQRwyVnNMwupJhNkLYx69n4erkSyp", path = "M/0/0")),
            ),
        )

        val p1_0 = Tx(
            hash = "bb8bea0f3ad694bf6ec7a6548186d92c15314274bb75a3c1cd04eea38f89c261-b28778f1-f392-40af-a1af-9db323408b6f",
            time = 0L, version = 1, locktime = 0, result = null, block_height = null,
            inputs = listOf(
            Inputs(vin = 0, sequence = 4294967295L, prev_out = prevOut(addr = "tb1qk8z025drzrxv3lsqtzvekpdl7xtmcxk2p88hxe", txid = "50a06ee16a571f89616ec1cec121247b283927b1afa9fbbfb4d75881a0ae59eb", value = 1945467355L, vout = 0, xpub = Xpub(m = "vpub5Yig39b82QaiLtyWHh1xQUUD8fdQNwoNvGEHzjWNLZHhw41DcXPMP4AQZ2oj3RQWdVGE27STV11qxXxvb7HSWbuyF4zeEPHMY8GLk5Vc8nd", path = "M/0/2"))),
            ),
            out = listOf(
            // no outputs (pubkey-scoped row),
            ),
        )

        val p1_1 = Tx(
            hash = "bb8bea0f3ad694bf6ec7a6548186d92c15314274bb75a3c1cd04eea38f89c261-b28778f1-f392-40af-a1af-9db323408b6f",
            time = 0L, version = 1, locktime = 0, result = null, block_height = null,
            inputs = listOf(
            // no inputs (pubkey-scoped row),
            ),
            out = listOf(
            Out(n = 2, value = 2500605L, addr = "tb1qp2v6wy4954c78kcqva95zl5v0ysslstnh7a3a2", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/106")),
            Out(n = 3, value = 2500605L, addr = "tb1qpcd35zq3p2jspuzx9ggckjk5la5gj0rg4k84ez", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/111")),
            Out(n = 4, value = 2500605L, addr = "tb1q92gmw4pd5rnmlze7s6t8l6h5mgp7jr0luq9rn2", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/113")),
            Out(n = 5, value = 2500605L, addr = "tb1q28uff43k6cfaqcsnvr079nkus963vudfnaa4pg", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/96")),
            Out(n = 6, value = 2500605L, addr = "tb1q2a28yq8lck5trysmz56x9cmvagnd7ycjje65d4", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/104")),
            Out(n = 7, value = 2500605L, addr = "tb1qvvdc92dz06w5jklupnhcsswg8cy5yvt9yn7p8k", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/114")),
            Out(n = 8, value = 2500605L, addr = "tb1qw9qcrjs9hnw74l5fp92j4vf2gnm4daeaqme88s", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/105")),
            Out(n = 9, value = 2500605L, addr = "tb1qw3pxhseujwzmedkddlnssp006cdv76mk9qac44", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/108")),
            Out(n = 10, value = 2500605L, addr = "tb1q008v2ec2jukf0krhks05c0l4udq9257z9ueqa9", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/110")),
            Out(n = 11, value = 2500605L, addr = "tb1qng5xg5rt07dgn9r9cg2cweqmzjk86k6se4yash", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/107")),
            Out(n = 12, value = 2500605L, addr = "tb1qn5whc5lp7px53rlrnk2p3v98qq7guvf3xm54xn", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/99")),
            Out(n = 13, value = 2500605L, addr = "tb1q4qy8sa2d5tyes6t3yj99z4jn3d0amn5a0a80z8", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/97")),
            Out(n = 14, value = 2500605L, addr = "tb1qkv5ljfy5n42e2fagcwrnzpqcxpkrcavusm374z", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/98")),
            Out(n = 15, value = 2500605L, addr = "tb1qcq4m4leazy6pgjkmjfzf5pvwxep5nks2vxnndp", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/109")),
            Out(n = 16, value = 2500605L, addr = "tb1q6wlzlf6d88stmpwu658tltpflcjshz647anj6c", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/112")),
            Out(n = 17, value = 2500605L, addr = "tb1q64fpwcust0dfxyfx4p9wstka8u4lg74eyzs6th", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/100")),
            Out(n = 18, value = 2500605L, addr = "tb1qmfjr8n626quntmctkqgk6x4t4k43y5zh7tdcgq", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/95")),
            Out(n = 19, value = 2500605L, addr = "tb1qm6s58wtx6h7frdx4xfl6uw3nf28ev64svma394", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/103")),
            Out(n = 20, value = 2500605L, addr = "tb1q7wqt5sjp4qsm4uy9dwesc6q3t93grzt56rpar0", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/102")),
            Out(n = 21, value = 2500605L, addr = "tb1qldp5gzrpj8zca8ualh5v5kwmxmp9lxr2aemqt3", xpub = Xpub(m = "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe", path = "M/0/101")),
            ),
        )

        val p1_2 = Tx(
            hash = "bb8bea0f3ad694bf6ec7a6548186d92c15314274bb75a3c1cd04eea38f89c261-b28778f1-f392-40af-a1af-9db323408b6f",
            time = 0L, version = 1, locktime = 0, result = null, block_height = null,
            inputs = listOf(
            // no inputs (pubkey-scoped row),
            ),
            out = listOf(
            Out(n = 22, value = 1895329353L, addr = "tb1qcc7wyrv9rnf7jyeh0jvm2cr397wjj48cxdelqz", xpub = Xpub(m = "vpub5Yig39bGN57gLvDjA3S9JjXE7KRueWiNRUGZUA5tzcFQ6m5EAziMCi9aFaySGvvrkCpTjqKyDJdsShHQRwyVnNMwupJhNkLYx69n4erkSyp", path = "M/0/1")),
            ),
        )

    @Test
    fun mergedPartialRowsClassifyTx0ViaXpubIdentity() {
        for (partials in listOf(listOf(p0_0, p0_1, p0_2),
                                listOf(p1_0, p1_1, p1_2))) {
            val merged = WhirlpoolBackfill.mergePartialTxs(partials, cid)
            assertEquals(1, merged.size)
            val view = WhirlpoolTxAdapter.toView(merged[0], cid, accountMap)
            assertEquals(WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L2),
                WhirlpoolDetector.classify(view))
        }
    }

    @Test
    fun firstPartialAloneStaysUnknown() {
        val view = WhirlpoolTxAdapter.toView(p0_0, cid, accountMap)
        assertEquals(WhirlpoolClassification.Unknown, WhirlpoolDetector.classify(view))
    }

    @Test
    fun mergedWithoutAccountMapStaysUnknownNotMisclassified() {
        val merged = WhirlpoolBackfill.mergePartialTxs(listOf(p0_0, p0_1, p0_2), cid)
        val view = WhirlpoolTxAdapter.toView(merged[0], cid)
        assertEquals(WhirlpoolClassification.Unknown, WhirlpoolDetector.classify(view))
    }
}
