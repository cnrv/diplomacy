// See LICENSE.SiFive for license details.

package chipsalliance.diplomacy

import Chisel._
import chisel3.{RawModule, MultiIOModule, withClockAndReset}
import chisel3.internal.sourceinfo.{SourceInfo, SourceLine, UnlocatableSourceInfo}
import freechips.rocketchip.config.Parameters
import scala.collection.immutable.{SortedMap,ListMap}
import scala.util.matching._

/** a instance extends from [[LazyModuleImp]] should implement:
  * {{{
  *   lazy val module = new LazyModuleImp(this) {
  *     ???
  *   }
  * }}}
  * when executing [[chisel3.stage.ChiselGeneratorAnnotation]], all [[LazyModuleImp]] will be instantiated at that time.
  *
  * But notice [[LazyModule]] is not lazy, [[LazyModuleImp]] is.
  * [[LazyModule]] is a handle to [[LazyModuleImp]],
  * and [[LazyModule]] will couple with [[BaseNode]], exists in the [[BaseNode]] lifetime.
  * [[LazyModuleImp]] contains the real circuit implementation.
  * In order to postpone the elaboration time, the real circuit should be set to lazy.
  *
  * A [[LazyModule]] can have multi node at the same time. Generally, [[BaseNode]] should be an instance of [[LazyModule]],
  * {{{
  *   class DemoLazyModule extends LazyModule {
  *     val node1: BaseNode = SomeNode1()
  *     val node2: BaseNode = SomeNode2()
  *   }
  * }}}
  * when [[LazyModule]] is getting instanced, which will always looks like `val someLm = LazyModule(new DemoLazyModule)`,
  * codes inside `DemoLazyModule` will be executed, since `node1` and `node2` are not lazy, when executing them,
  * they will push `this` to current [[LazyModule]].
  *
  * */
abstract class LazyModule()(implicit val p: Parameters)
{
  protected[diplomacy] var children = List[LazyModule]()
  protected[diplomacy] var nodes = List[BaseNode]()
  /** Author used design a [[info]] for source info locator, but not implemented.
    * thus leave [[UnlocatableSourceInfo]] behind.*/
  protected[diplomacy] var info: SourceInfo = UnlocatableSourceInfo
  protected[diplomacy] val parent = LazyModule.scope

  // code snippets from 'InModuleBody' injection
  protected[diplomacy] var inModuleBody = List[() => Unit]()

  /** get the [[parents]] from [[LazyModule]] singleton:
    * if will return the full stack of this [[LazyModule]]
    * */
  def parents: Seq[LazyModule] = parent match {
    case None => Nil
    case Some(x) => x +: x.parents
  }

  /** [[LazyModule.scope]] stack push */
  LazyModule.scope = Some(this)
  /** ask parents to add this class into there [[children]],
    * This is a important difference between [[chisel3.Module]] and [[LazyModule]]
    * [[LazyModule]] can access other [[Module]]'s variable, before evaluation.
    * So no need to access [[chisel3.internal.Builder]] for dangerous hacking.
    * */
  parent.foreach(p => p.children = this :: p.children)

  // suggestedName accumulates Some(names), taking the final one. Nones are ignored.
  private var suggestedNameVar: Option[String] = None
  def suggestName(x: String): this.type = suggestName(Some(x))
  def suggestName(x: Option[String]): this.type = {
    x.foreach { n => suggestedNameVar = Some(n) }
    this
  }

  private def findClassName(c: Class[_]): String = {
    val n = c.getName.split('.').last
    if (n.contains('$')) findClassName(c.getSuperclass) else n
  }

  lazy val className = findClassName(getClass)
  lazy val suggestedName = suggestedNameVar.getOrElse(className)
  lazy val desiredName = className // + hashcode?

  def name = suggestedName // className + suggestedName ++ hashcode ?
  def line = sourceLine(info)

  // Accessing these names can only be done after circuit elaboration!
  lazy val moduleName = module.name // The final Verilog Module name
  lazy val pathName = module.pathName
  lazy val instanceName = pathName.split('.').last // The final Verilog instance name

  /** [[LazyModule]] depends on the Scala evaluation:
    * see https://stackoverflow.com/questions/7484928/what-does-a-lazy-val-do
    * but the key is not whether this module is lazy or not,
    * the key idea is module is a function which will be evaluated when needed.
    * so in this abstract class [[module]] is defined as a method
    * */
  def module: LazyModuleImpLike

  def omitGraphML: Boolean = !nodes.exists(!_.omitGraphML) && !children.exists(!_.omitGraphML)

  /** generate the [[graphML]]*/
  lazy val graphML: String = parent.map(_.graphML).getOrElse {
    val buf = new StringBuilder
    buf ++= "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
    buf ++= "<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\" xmlns:y=\"http://www.yworks.com/xml/graphml\">\n"
    buf ++= "  <key for=\"node\" id=\"n\" yfiles.type=\"nodegraphics\"/>\n"
    buf ++= "  <key for=\"edge\" id=\"e\" yfiles.type=\"edgegraphics\"/>\n"
    buf ++= "  <key for=\"node\" id=\"d\" attr.name=\"Description\" attr.type=\"string\"/>\n"
    buf ++= "  <graph id=\"G\" edgedefault=\"directed\">\n"
    nodesGraphML(buf, "    ")
    edgesGraphML(buf, "    ")
    buf ++= "  </graph>\n"
    buf ++= "</graphml>\n"
    buf.toString
  }

  private val index = { LazyModule.index = LazyModule.index + 1; LazyModule.index }

  private def nodesGraphML(buf: StringBuilder, pad: String) {
    buf ++= s"""${pad}<node id=\"${index}\">\n"""
    buf ++= s"""${pad}  <data key=\"n\"><y:ShapeNode><y:NodeLabel modelName=\"sides\" modelPosition=\"w\" rotationAngle=\"270.0\">${instanceName}</y:NodeLabel></y:ShapeNode></data>\n"""
    buf ++= s"""${pad}  <data key=\"d\">${moduleName} (${pathName})</data>\n"""
    buf ++= s"""${pad}  <graph id=\"${index}::\" edgedefault=\"directed\">\n"""
    nodes.filter(!_.omitGraphML).foreach { n =>
      buf ++= s"""${pad}    <node id=\"${index}::${n.index}\">\n"""
      buf ++= s"""${pad}      <data key=\"e\"><y:ShapeNode><y:Shape type="Ellipse"/></y:ShapeNode></data>\n"""
      buf ++= s"""${pad}      <data key=\"d\">${n.formatNode}, \n${n.nodedebugstring}</data>\n"""
      buf ++= s"""${pad}    </node>\n"""
    }
    children.filter(!_.omitGraphML).foreach { _.nodesGraphML(buf, pad + "    ") }
    buf ++= s"""${pad}  </graph>\n"""
    buf ++= s"""${pad}</node>\n"""
  }
  private def edgesGraphML(buf: StringBuilder, pad: String) {
    nodes.filter(!_.omitGraphML) foreach { n => n.outputs.filter(!_._1.omitGraphML).foreach { case (o, edge) =>
      val RenderedEdge(colour, label, flipped) = edge
      buf ++= pad
      buf ++= "<edge"
      if (flipped) {
        buf ++= s""" target=\"${index}::${n.index}\""""
        buf ++= s""" source=\"${o.lazyModule.index}::${o.index}\">"""
      } else {
        buf ++= s""" source=\"${index}::${n.index}\""""
        buf ++= s""" target=\"${o.lazyModule.index}::${o.index}\">"""
      }
      buf ++= s"""<data key=\"e\"><y:PolyLineEdge>"""
      if (flipped) {
        buf ++= s"""<y:Arrows source=\"standard\" target=\"none\"/>"""
      } else {
        buf ++= s"""<y:Arrows source=\"none\" target=\"standard\"/>"""
      }
      buf ++= s"""<y:LineStyle color=\"${colour}\" type=\"line\" width=\"1.0\"/>"""
      buf ++= s"""<y:EdgeLabel modelName=\"centered\" rotationAngle=\"270.0\">${label}</y:EdgeLabel>"""
      buf ++= s"""</y:PolyLineEdge></data></edge>\n"""
    } }
    children.filter(!_.omitGraphML).foreach { c => c.edgesGraphML(buf, pad) }
  }

  def nodeIterator(iterfunc: (LazyModule) => Unit): Unit = {
    iterfunc(this)
    children.foreach( _.nodeIterator(iterfunc) )
  }

  def getChildren = children
}

object LazyModule
{
  /** current [[LazyModue]] scope, default is [[None]],
    * it will be dynamically set by [[LazyScope.apply]] and [[LazyModule.apply]],
    * specifically, it is a stack of [[LazyModule]],
    * */
  protected[diplomacy] var scope: Option[LazyModule] = None
  /** index of [[LazyModule]], notice there is no No.0 module */
  private var index = 0

  def apply[T <: LazyModule](bc: T)(implicit valName: ValName, sourceInfo: SourceInfo): T = {
    /** Make sure the user put LazyModule around modules in the correct order
      * If this require fails, probably some grandchild was missing a LazyModule
      * or you applied LazyModule twice
      * */
    require (scope.isDefined, s"LazyModule() applied to ${bc.name} twice ${sourceLine(sourceInfo)}")
    require (scope.get eq bc, s"LazyModule() applied to ${bc.name} before ${scope.get.name} ${sourceLine(sourceInfo)}")
    /** [[LazyModule.scope]] stack pop,
      * since [[LazyModule]] is not a lazy val,
      * when [[apply[T <: LazyModule](bc: T)]], [[bc]] has been evaluated, stack push inside [[LazyModule]]
      * */
    scope = bc.parent
    bc.info = sourceInfo
    if (!bc.suggestedNameVar.isDefined) bc.suggestName(valName.name)
    bc
  }
}

sealed trait LazyModuleImpLike extends RawModule
{
  val wrapper: LazyModule
  val auto: AutoBundle
  protected[diplomacy] val dangles: Seq[Dangle]

  // .module had better not be accessed while LazyModules are still being built!
  require (!LazyModule.scope.isDefined, s"${wrapper.name}.module was constructed before LazyModule() was run on ${LazyModule.scope.get.name}")

  override def desiredName = wrapper.desiredName
  suggestName(wrapper.suggestedName)

  implicit val p = wrapper.p

  /** [[instantiate]] will be called when a instance of [[LazyModuleImp]] is created. */
  protected[diplomacy] def instantiate() = {
    val childDangles = wrapper.children.reverse.flatMap { c =>
      implicit val sourceInfo = c.info
      /** calling [[c.module]] will push the real [[Module]] into [[chisel3.internal.Builder]],
        * notice this place is not calling [[instantiate]], it just push the [[c.module]] to Builder.
        * */
      val mod = Module(c.module)
      /** ask each child to finish instantiate.*/
      mod.finishInstantiate()
      /** return dangles of each child. */
      mod.dangles
    }

    /** ask each node in the [[LazyModule]] to call [[BaseNode.instantiate]].
      * if will return a sequence of [[Dangle]] of these [[BaseNode]]*/
    val nodeDangles = wrapper.nodes.reverse.flatMap(_.instantiate())
    /** add all node and child dangle together.*/
    val allDangles = nodeDangles ++ childDangles

    val pairing = SortedMap(allDangles.groupBy(_.source).toSeq:_*)
    /** make the connection between source and sink.*/
    val done = Set() ++ pairing.values.filter(_.size == 2).map { case Seq(a, b) =>
      require (a.flipped != b.flipped)
      if (a.flipped) { a.data <> b.data } else { b.data <> a.data }
      a.source
    }
    /** find all not connected [[Dangle]] */
    val forward = allDangles.filter(d => !done(d.source))
    /** generate [[IO]] from [[forward]]*/
    val auto = IO(new AutoBundle(forward.map { d => (d.name, d.data, d.flipped) }:_*))
    /** give these [[IO]] a [[Dangle]] to make it easy to access from father nodes.
      * connect the generated IO with internal forward data*/
    val dangles = (forward zip auto.elements) map { case (d, (_, io)) =>
      if (d.flipped) { d.data <> io } else { io <> d.data }
      d.copy(data = io, name = wrapper.suggestedName + "_" + d.name)
    }
    /** push all [[LazyModule.inModuleBody]] to [[chisel3.internal.Builder]]*/
    wrapper.inModuleBody.reverse.foreach { _() }
    /** return [[IO]] and [[Dangle]] of this [[LazyModuleImp]]*/
    (auto, dangles)
  }

  /** Ask each [[BaseNode]] in [[wrapper.nodes]] to call [[BaseNode.finishInstantiate]]
    * notice: There are 2 different finishInstantiate:
    *   [[LazyModuleImp.finishInstantiate]] and [[BaseNode.finishInstantiate]],
    *   the former is a wrapper to the latter
    * */
  protected[diplomacy] def finishInstantiate() {
    wrapper.nodes.reverse.foreach { _.finishInstantiate() }
  }
}

class LazyModuleImp(val wrapper: LazyModule) extends MultiIOModule with LazyModuleImpLike {
  val (auto, dangles) = instantiate()
}

class LazyRawModuleImp(val wrapper: LazyModule) extends RawModule with LazyModuleImpLike {
  // These wires are the default clock+reset for all LazyModule children
  // It is recommended to drive these even if you manually shove most of your children
  // Otherwise, anonymous children (Monitors for example) will not be clocked
  val childClock = Wire(Clock())
  val childReset = Wire(Bool())
  childClock := Bool(false).asClock
  childReset := Bool(true)
  val (auto, dangles) = withClockAndReset(childClock, childReset) {
    instantiate()
  }
}

/** when class extends [[SimpleLazyModule]],
  * class should also extends [[LazyModuleImpLike]],
  * todo: more clear documentation.
  * */
class SimpleLazyModule(implicit p: Parameters) extends LazyModule
{
  lazy val module = new LazyModuleImp(this)
}

trait LazyScope
{
  this: LazyModule =>
  override def toString: String = s"LazyScope named $name"
  /** manage the [[LazyScope]], when calling [[apply]] function,
    * [[LazyModule.scope]] will be altered.
    * */
  def apply[T](body: => T) = {
    val saved = LazyModule.scope
    /** [[LazyModule.scope]] stack push*/
    LazyModule.scope = Some(this)
    /** because this is val, body will be evaluated. when assign to [[out]]
      * thus if [[body]] contains a internal [[LazyScope]], another [[out]] will be evaluated.
      * */
    val out = body
    /** when [[out]] is evaluated, try to escape from where. */
    require (LazyModule.scope.isDefined, s"LazyScope ${name} tried to exit, but scope was empty!")
    require (LazyModule.scope.get eq this, s"LazyScope ${name} exited before LazyModule ${LazyModule.scope.get.name} was closed")
    /** stack pop*/
    LazyModule.scope = saved
    out
  }
}

object LazyScope
{
  def apply[T](body: => T)(implicit valName: ValName, p: Parameters): T = {
    val scope = LazyModule(new SimpleLazyModule with LazyScope)
    scope { body }
  }
  def apply[T](name: String)(body: => T)(implicit p: Parameters): T = {
    apply(body)(ValName(name), p)
  }
}

case class HalfEdge(serial: Int, index: Int) extends Ordered[HalfEdge] {
  import scala.math.Ordered.orderingToOrdered
  def compare(that: HalfEdge) = HalfEdge.unapply(this) compare HalfEdge.unapply(that)
}
/** [[Dangle]] is a handle to [[LazyModule]] and [[BaseNode]].
  * It will be returned by [[LazyModuleImpLike.instantiate]] and [[BaseNode.instantiate]],
  * It contains the IO information of a [[LazyModule]] and [[BaseNode]]
  * */
case class Dangle(source: HalfEdge, sink: HalfEdge, flipped: Boolean, name: String, data: Data)

/** [[AutoBundle]] will construct the [[Bundle]] for [[LazyModule]] in [[LazyModuleImpLike.instantiate]],
  * @param elts is a sequence of data contains port (name, data, flipped),
  *             flipped: true -> Input
  *                      false -> Output
  * */
final class AutoBundle(elts: (String, Data, Boolean)*) extends Record {
  // We need to preserve the order of elts, despite grouping by name to disambiguate things
  val elements = ListMap() ++ elts.zipWithIndex.map(makeElements).groupBy(_._1).values.flatMap {
    /** if name is unique, it will return a Seq[index -> (name -> data)]*/
    case Seq((key, element, i)) => Seq(i -> (key -> element))
    /** if name is not unique, name will append with j, and return `Seq[index -> (s"${name}_${j}" -> data)]` */
    case seq => seq.zipWithIndex.map { case ((key, element, i), j) => i -> (key + "_" + j -> element) }
  }.toList.sortBy(_._1).map(_._2)
  require (elements.size == elts.size)

  /** trim final "(_[0-9]+)*$" in the name,
    * flip data with flipped
    * */
  private def makeElements(tuple: ((String, Data, Boolean), Int)) = {
    val ((key, data, flip), i) = tuple
    // trim trailing _0_1_2 stuff so that when we append _# we don't create collisions
    val regex = new Regex("(_[0-9]+)*$")
    val element = if (flip) data.cloneType.flip else data.cloneType
    (regex.replaceAllIn(key, ""), element, i)
  }

  override def cloneType = (new AutoBundle(elts:_*)).asInstanceOf[this.type]
}

trait ModuleValue[T]
{
  def getWrappedValue: T
}

object InModuleBody
{
  /** code snippet injection.
    * [[InModuleBody.apply(body)]] will inject body to current [[LazyModule.inModuleBody]],
    * and called by [[LazyModule.module]], it is extended from [[LazyModuleImpLike]],
    * which will call [[LazyModuleImpLike.instantiate]] to push [[LazyModule]] to [[chisel3.internal.Builder]] finally.
    * */
  def apply[T](body: => T): ModuleValue[T] = {
    require (LazyModule.scope.isDefined, s"InModuleBody invoked outside a LazyModule")
    val scope = LazyModule.scope.get
    val out = new ModuleValue[T] {
      var result: Option[T] = None
      def execute() { result = Some(body) }
      def getWrappedValue = {
        require (result.isDefined, s"InModuleBody contents were requested before module was evaluated!")
        result.get
      }
    }
    /** notice: here didn't call [[out.execute]] function,
      * it is a `() => Unit` val which will be executed later*/
    scope.inModuleBody = (out.execute _) +: scope.inModuleBody
    out
  }
}
