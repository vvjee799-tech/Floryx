-keep class kotlinx.serialization.** { *; }
-dontwarn org.slf4j.impl.StaticLoggerBinder
# Optional PdfBox JPX image decoder; document text extraction does not require it.
-dontwarn com.gemalto.jp2.JP2Decoder
