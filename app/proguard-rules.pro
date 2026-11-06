# 保留通过反射/序列化访问的模型
-keep class com.xxkg.board.model.** { *; }

# 自定义 View 由 XML 反射构造，R8 下保留构造函数
-keep class com.xxkg.board.TimetableView { public <init>(...); }
-keep class com.xxkg.board.TimetableHeaderView { public <init>(...); }
-keep class com.xxkg.board.WeekChipsView { public <init>(...); }
-keep class com.xxkg.board.MaxHeightScrollView { public <init>(...); }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**

# PDFBox 的可选 JPEG2000 解码器依赖，未打包但不影响普通 PDF
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**
# PDFBox 通过 ServiceLoader/反射加载字体与过滤器
-keep class com.tom_roush.pdfbox.pdmodel.font.** { *; }
-keep class com.tom_roush.pdfbox.filter.** { *; }
