package com.masaibar.datastore.inspector.gradle

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import java.io.File

class RuntimeMarkerManifestSpec : DescribeSpec({
  describe("runtime-core AndroidManifest") {
    context("debuggable variantへRuntime Providerを注入するとき") {
      val manifest = File("../runtime-core/src/main/AndroidManifest.xml").readText()

      it("既存authorityとversion付きRuntime markerを同じ非exported Providerへ宣言する") {
        manifest shouldContain
          "\${applicationId}.datastore_inspector_init;" +
          "\${applicationId}.datastore_inspector_runtime_v1"
        manifest shouldContain "android:exported=\"false\""
        manifest shouldContain "DataStoreInspectorInitProvider"
      }
    }
  }
})
