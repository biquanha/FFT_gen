// See README.md for license details.


name := "chisel-module-template"

version := "6.7.0"

// 升级到 Chisel 6.7（需要 Scala 2.13）
scalaVersion := "2.13.12"

// 调整JVM内存配置：可通过环境变量覆盖，默认更保守以避免内存不足
// 环境变量：SBT_XMX (默认 4G), SBT_XMS (默认 512M)
{
  val xmx = sys.env.getOrElse("SBT_XMX", "4G")
  val xms = sys.env.getOrElse("SBT_XMS", "512M")
  javaOptions ++= Seq(s"-Xmx${xmx}", s"-Xms${xms}")
}

// 不再支持 2.11/2.12，使用 2.13
crossScalaVersions := Seq(scalaVersion.value)

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  Resolver.sonatypeRepo("releases")
)

// 依赖：Chisel 6.7 + chiseltest（替代旧 iotesters）
libraryDependencies ++= Seq(
  "org.chipsalliance" %% "chisel" % sys.props.getOrElse("chiselVersion", "6.7.0"),
  // chiseltest 6.0.0（edu.berkeley.cs 组）
  "edu.berkeley.cs"   %% "chiseltest" % sys.props.getOrElse("chiselTestVersion", "6.0.0") % Test,
  "org.scalatest"     %% "scalatest" % "3.2.18" % Test
)

// Chisel 6 需要编译器插件支持
ThisBuild / scalacOptions ++= Seq("-Ymacro-annotations")
// chisel-plugin 需要 full cross（artifact 名含 2.13.12）
ThisBuild / libraryDependencies ++= {
  val sv = scalaVersion.value
  val ver = sys.props.getOrElse("chiselVersion", "6.7.0")
  Seq(compilerPlugin("org.chipsalliance" % s"chisel-plugin_${sv}" % ver))
}

// 过滤旧版 iotesters 测试以避免编译
Test / excludeFilter := (Test / excludeFilter).value ||
  new SimpleFileFilter(f => f.getName == "FFTTest.scala" || f.getPath.contains("/src/test/scala/gcd/"))

// 过滤旧版/备份代码与发射脚本，避免不兼容 API 的编译错误
Compile / excludeFilter := (Compile / excludeFilter).value ||
  new SimpleFileFilter(f => Set(
    "VerilogEmitter.scala",
    "FFTCores.scala.backup_before_rewrite",
    "TOP.scala.with_fifo"
  ).contains(f.getName))

// 调整编译选项，避免 2.13 下的 -Xsource 警告
scalacOptions := Seq("-deprecation", "-feature", "-unchecked")
javacOptions := Seq("-source", "1.8", "-target", "1.8")

// 关闭旧的 -Xsource 强制设置
