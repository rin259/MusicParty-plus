package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class Base64MitigationTest {
    @Test void testJvmDisablesIntrinsicAndNormalBase64StillWorks() {
        var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        assertThat(bean.getVMOption("UseBASE64Intrinsics").getValue()).isEqualTo("false");
        byte[] data = "MusicParty regression".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(Base64.getDecoder().decode(Base64.getEncoder().encode(data))).isEqualTo(data);
    }
}
