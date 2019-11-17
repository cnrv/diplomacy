import mill._
import mill.scalalib._
import mill.scalalib.publish._
import mill.scalalib.scalafmt._

object diplomacy extends ScalaModule with PublishModule with ScalafmtModule {
  def scalaVersion = "2.12.10"

  override def ivyDeps = Agg(
    ivy"edu.berkeley.cs::chisel3:3.2-SNAPSHOT",
    ivy"edu.berkeley.cs::rocketchip:1.2-SNAPSHOT",
    ivy"edu.berkeley.cs::chisel-testers2:0.1-SNAPSHOT",
    ivy"com.lihaoyi::os-lib:0.2.7",
    ivy"com.lihaoyi::upickle:0.8.0",
    ivy"com.lihaoyi::ammonite-ops:1.7.1"
  )

  def publishVersion = "1.2-SNAPSHOT"

  def pomSettings = PomSettings(
    description = "diplomacy",
    organization = "me.sequencer",
    url = "https://github.com/sequencer/diplomacy",
    licenses = Seq(License.`BSD-3-Clause`),
    versionControl = VersionControl.github("sequencer", "diplomacy"),
    developers = Seq(
      Developer("sequencer", "Jiuyang Liu", "https://github.com/sequencer")
    )
  )

  object tests extends Tests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.1")

    def testFrameworks = Seq("utest.runner.Framework")
  }

}
