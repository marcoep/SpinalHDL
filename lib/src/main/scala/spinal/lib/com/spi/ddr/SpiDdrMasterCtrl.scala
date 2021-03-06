package spinal.lib.com.spi.ddr


import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.com.spi.SpiKind
import spinal.lib.fsm.{State, StateMachine}
import spinal.lib.io.TriState

import scala.collection.mutable.ArrayBuffer

case class DdrOutput(rate : Int) extends Bundle with IMasterSlave{
  val write = Bits(rate bits)

  override def asMaster(): Unit = {
    out(write)
  }

  def toTriState(): TriState[Bool] ={
    val io = TriState(Bool)
    val clk = ClockDomain.readClockWire
    val writeBuffer = RegNext(write)
    io.write := (clk ? writeBuffer(0))| writeBuffer(1)
    io
  }
}

case class DdrPin(rate : Int) extends Bundle with IMasterSlave{
  val writeEnable = Bool
  val read,write = Bits(rate bits)

  override def asMaster(): Unit = {
    out(write,writeEnable)
    in(read)
  }

  def toTriState(): TriState[Bool] ={
    val io = TriState(Bool)
    val clk = ClockDomain.readClockWire
    io.writeEnable := writeEnable
    val writeBuffer = RegNext(write)
    io.write := (clk ? writeBuffer(0))| writeBuffer(1)
    def cd(edge : EdgeKind) = ClockDomain.current.clone(config = ClockDomain.current.config.copy(clockEdge = edge))
    read(0) := cd(RISING)(RegNext(io.read))
    read(1) := cd(FALLING)(RegNext(io.read))
    io
  }
}


case class SpiDdrParameter(dataWidth : Int,
                           ioRate : Int,
                           ssWidth : Int)

case class SpiDdrMaster(p : SpiDdrParameter) extends Bundle with IMasterSlave{
  import p._

  val sclk = DdrOutput(p.ioRate)
  val data = Vec(DdrPin(p.ioRate), dataWidth)
  val ss   = if(ssWidth != 0) Bits(ssWidth bits) else null

  override def asMaster(): Unit = {
    master(sclk)
    if(ssWidth != 0) out(ss)
    data.foreach(master(_))
  }
}


object SpiDdrMasterCtrl {
  def apply(p : Parameters) = new TopLevel(p)



  def main(args: Array[String]): Unit = {
    SpinalVerilog(new TopLevel(Parameters(8,12,SpiDdrParameter(dataWidth = 4,ssWidth = 3, ioRate = 1)).addFullDuplex(0)))
  }




  //  case class ParameterMapping(position : Int, phase : Int)
  case class Mod(id : Int, clkRate : Int, ddr : Boolean, dataWidth : Int, writeMapping : Seq[Int], readMapping : Seq[Int]){
    assert(writeMapping.length == readMapping.length)
    def bitrate = readMapping.length
  }
  case class Parameters(dataWidth : Int,
                        timerWidth : Int,
                        spi : SpiDdrParameter,
                        mods : ArrayBuffer[Mod] = ArrayBuffer()){
    def ssGen = spi.ssWidth != 0
    def addFullDuplex(id : Int, rate : Int = 1, ddr : Boolean = false, dataWidth : Int = 8): this.type = {
      mods += Mod(id, rate, ddr, dataWidth, List(0), List(1))
      this
    }
    def addHalfDuplex(id : Int, rate : Int,ddr : Boolean, spiWidth : Int, dataWidth : Int = 8): this.type = {
      val low = 0 until spiWidth
      val top = spi.dataWidth until spi.dataWidth + spiWidth
      if(ddr)
        mods += Mod(id, rate, ddr, spiWidth, top ++ low, top ++ low)
      else
        mods += Mod(id, rate, ddr, spiWidth, low, low) //TODO should it be low high ?
      this
    }
//    def addAllMods(): this.type ={
//      if(dataWidth >= 2) addFullDuplex(0)
//      for((spiWidth, o) <- (2 to spi.dataWidth).filter(isPow2(_)).zipWithIndex){
//        addHalfDuplex(2+o*2, spiWidth, false)
//        if(spiWidth*2 <= dataWidth) addHalfDuplex(2+o*2 + 1, spiWidth, true)
//      }
//      ???
//      this
//    }
  }

  case class Config(p: Parameters) extends Bundle {
    val kind = SpiKind()
    val sclkToogle = UInt(p.timerWidth bits)
    val mod = in UInt(log2Up(p.mods.map(_.id).max + 1) bits)

    val ss = ifGen(p.ssGen) (new Bundle {
      val activeHigh = Bits(p.spi.ssWidth bits)
      val setup = UInt(p.timerWidth bits)
      val hold = UInt(p.timerWidth bits)
      val disable = UInt(p.timerWidth bits)
    })
  }

  case class Cmd(p: Parameters) extends Bundle{
    val kind = Bool
    val read, write = Bool
    val data = Bits(p.dataWidth bits)

    def isData = !kind
    def isSs = kind
    def getSsEnable = data.msb
    def getSsId = U(data(0, log2Up(p.spi.ssWidth) bits))
  }

  case class Rsp(p: Parameters) extends Bundle{
    val data = Bits(p.dataWidth bits)
  }


  case class MemoryMappingParameters(ctrl : Parameters,
                                     cmdFifoDepth : Int = 32,
                                     rspFifoDepth : Int = 32,
                                     xip : XipBusParameters = null)

  case class XipBusParameters(addressWidth : Int, dataWidth : Int)
  case class XipBus(p : XipBusParameters) extends Bundle with IMasterSlave{
    val cmd = Stream(UInt(p.addressWidth bits))
    val rsp = Flow(Bits(p.dataWidth bits))

    override def asMaster(): Unit = {
      master(cmd)
      slave(rsp)
    }
  }

  class TopLevel(val p: Parameters) extends Component {
    setDefinitionName("SpiDdrMasterCtrl")

    val io = new Bundle {
      val config = in(Config(p))
      val cmd = slave(Stream(Cmd(p)))
      val rsp = master(Flow(Rsp(p)))
      val spi = master(master(SpiDdrMaster(p.spi)))


      def driveFrom(bus : BusSlaveFactory, baseAddress : Int = 0)(mapping : MemoryMappingParameters) = new Area {
        import mapping._
        require(cmdFifoDepth >= 1)
        require(rspFifoDepth >= 1)

        require(cmdFifoDepth < 32.kB)
        require(rspFifoDepth < 32.kB)

        //CMD
        val cmdLogic = new Area {
          val streamUnbuffered = Stream(Cmd(p))
          streamUnbuffered.valid := bus.isWriting(address = baseAddress + 0)
          bus.nonStopWrite(streamUnbuffered.data, bitOffset = 0)
          bus.nonStopWrite(streamUnbuffered.write, bitOffset = 8)
          bus.nonStopWrite(streamUnbuffered.read, bitOffset = 9)
          bus.nonStopWrite(streamUnbuffered.kind, bitOffset = 11)


          bus.createAndDriveFlow(Cmd(p),address = baseAddress + 0).toStream
          val (stream, fifoAvailability) = streamUnbuffered.queueWithAvailability(cmdFifoDepth)
          cmd << stream
          bus.read(fifoAvailability, address = baseAddress + 4, 16)
        }

        //RSP
        val rspLogic = new Area {
          val feedRsp = True
          val (stream, fifoOccupancy) = rsp.takeWhen(feedRsp).queueWithOccupancy(rspFifoDepth)
          bus.readStreamNonBlocking(stream, address = baseAddress + 0, validBitOffset = 31, payloadBitOffset = 0)
          bus.read(fifoOccupancy, address = baseAddress + 0, 16)
        }

        //Status
        val interruptCtrl = new Area {
          val cmdIntEnable = bus.createReadAndWrite(Bool, address = baseAddress + 4, 0) init(False)
          val rspIntEnable  = bus.createReadAndWrite(Bool, address = baseAddress + 4, 1) init(False)
          val cmdInt = bus.read(cmdIntEnable & !cmdLogic.stream.valid, address = baseAddress + 4, 8)
          val rspInt = bus.read(rspIntEnable &  rspLogic.stream.valid, address = baseAddress + 4, 9)
          val interrupt = rspInt || cmdInt
        }

        //Configs
        bus.drive(config.kind, baseAddress + 8, bitOffset = 0)
        bus.drive(config.mod, baseAddress + 8, bitOffset = 4)
        bus.drive(config.sclkToogle, baseAddress + 0x20)
        bus.drive(config.ss.setup,   baseAddress + 0x24)
        bus.drive(config.ss.hold,    baseAddress + 0x28)
        bus.drive(config.ss.disable, baseAddress + 0x2C)



        val xip = ifGen(mapping.xip != null) (new Area{
          val xipBus = XipBus(mapping.xip)
          val enable = RegInit(False)
          val instructionEnable = Reg(Bool)
          val instructionData = Reg(Bits(8 bits))
          val dummyCount = Reg(UInt(4 bits))
          val dummyData = Reg(Bits(8 bits))

          bus.write(enable, baseAddress + 0x40)
          bus.write(instructionData, baseAddress + 0x44, bitOffset = 0)
          bus.write(instructionEnable, baseAddress + 0x44, bitOffset = 8)
          bus.write(dummyData, baseAddress + 0x44, bitOffset = 16)
          bus.write(dummyCount, baseAddress + 0x44, bitOffset = 24)

          val fsm = new StateMachine{
            val doLoad, doPayload, done = False
            val loadedValid = RegInit(False)
            val loadedAddress = Reg(UInt(24 bits))
            val hit = loadedValid && loadedAddress === xipBus.cmd.payload

            val IDLE, INSTRUCTION, ADDRESS, DUMMY, PAYLOAD = State()
            setEntry(IDLE)

            when(enable){
              cmd.valid := False
              rspLogic.feedRsp := False
            }

            IDLE.whenIsActive{
              when(doLoad){
                cmd.valid := True
                cmd.kind := True
                cmd.data := 1 << cmd.data.high
                when(cmd.ready) {
                  loadedAddress := xipBus.cmd.payload
                  when(instructionEnable) {
                    goto(INSTRUCTION)
                  } otherwise {
                    goto(ADDRESS)
                  }
                }
              }
            }

            INSTRUCTION.whenIsActive{
              cmd.valid := True
              cmd.kind := False
              cmd.write := True
              cmd.read := False
              cmd.data := instructionData
              when(cmd.ready) {
                goto(ADDRESS)
              }
            }

            val counter = Reg(UInt(4 bits)) init(0)
            ADDRESS.onEntry(counter := 0)
            ADDRESS.whenIsActive{
              cmd.valid := True
              cmd.kind := False
              cmd.write := True
              cmd.read := False
              cmd.data := loadedAddress.subdivideIn(8 bits).reverse(counter(1 downto 0)).asBits
              when(cmd.ready) {
                counter := counter + 1
                when(counter === 2) {
                  goto(DUMMY)
                }
              }
            }

            DUMMY.onEntry(counter := 0)
            DUMMY.whenIsActive{
              cmd.valid := True
              cmd.kind := False
              cmd.write := True
              cmd.read := False
              cmd.data := dummyData
              when(cmd.ready) {
                counter := counter + 1
                when(counter === dummyCount) {
                  loadedValid := True
                  goto(PAYLOAD)
                }
              }
            }

            PAYLOAD.onEntry(counter := 0)
            PAYLOAD.whenIsActive{
              when(doPayload) {
                cmd.valid := True
                cmd.kind := False
                cmd.write := False
                cmd.read := True
                when(cmd.ready) {
                  counter := counter + 1
                  when(counter === mapping.xip.dataWidth / 8 - 1) {
                    done := True
                    counter := 0
                    loadedAddress := loadedAddress + mapping.xip.dataWidth / 8
                  }
                }
              }

              when(doLoad){
                cmd.valid := True
                cmd.kind := True
                cmd.data := 0
                when(cmd.ready) {
                  loadedValid := False
                  goto(IDLE)
                }
              }
            }

            always{
              when(!enable){
                goto(IDLE)
              }
            }
          }


          xipBus.cmd.ready := False
          when(enable){
            when(xipBus.cmd.valid){
              when(fsm.hit){
                fsm.doPayload := True
                xipBus.cmd.ready := fsm.done
              } otherwise {
                fsm.doLoad := True
              }
            }
          }

          val rspCounter = Counter(mapping.xip.dataWidth/8)
          val rspBuffer = Reg(Bits(mapping.xip.dataWidth-8 bits))
          when(enable && rsp.valid){
            rspCounter.increment()
            rspBuffer := rsp.payload ## (rspBuffer >> 8)
          }

          xipBus.rsp.valid := rspCounter.willOverflow
          xipBus.rsp.payload := rsp.payload ## rspBuffer

          when(!enable){
            xipBus.cmd.ready := True
            xipBus.rsp.valid := RegNext(xipBus.cmd.valid) init(False)
          }

        })
      }
    }

    val timer = new Area{
      val counter = Reg(UInt(p.timerWidth bits))
      val reset = False
      val ss = ifGen(p.ssGen) (new Area{
        val setupHit    = counter === io.config.ss.setup
        val holdHit     = counter === io.config.ss.hold
        val disableHit  = counter === io.config.ss.disable
      })
      val sclkToogleHit = counter === io.config.sclkToogle

      counter := counter + 1
      when(reset){
        counter := 0
      }
    }



    val widths = p.mods.map(m => m.bitrate).distinct.sorted
    val widthMax = widths.max






    val fsm = new Area {
      val state = RegInit(False)
      val counter = Reg(UInt(log2Up(p.dataWidth) bits)) init(0)
      val bitrateMax = p.mods.map(_.bitrate).max
      val counterPlus = counter +  io.config.mod.muxListDc(p.mods.map(m => m.id -> U(m.bitrate, log2Up(bitrateMax + 1) bits))).resized
      val fastRate = io.config.mod.muxListDc(p.mods.map(m => m.id -> Bool(m.clkRate != 1 || m.ddr)))
      val readFill, readDone = False
      val ss = RegInit(B((1 << p.spi.ssWidth) - 1, p.spi.ssWidth bits))
      io.spi.ss := ss

      io.cmd.ready := False
      when(io.cmd.valid) {
        when(io.cmd.isData) {
          timer.reset := timer.sclkToogleHit
          when(timer.sclkToogleHit){
            state := !state
          }
          when((timer.sclkToogleHit && state) || fastRate) {
            counter := counterPlus
            readFill := True
            when(counterPlus === 0){
              io.cmd.ready := True
              readDone := io.cmd.read
            }
          }
        } otherwise {
          if (p.ssGen) {
            when(io.cmd.getSsEnable) {
              ss(io.cmd.getSsId) := False
              when(timer.ss.setupHit) {
                io.cmd.ready := True
              }
            } otherwise {
              when(!state) {
                when(timer.ss.holdHit) {
                  state := True
                  timer.reset := True
                }
              } otherwise {
                ss(io.cmd.getSsId) := True
                when(timer.ss.disableHit) {
                  io.cmd.ready := True
                }
              }
            }
          }
        }
      }

      //Idle states
      when(!io.cmd.valid || io.cmd.ready){
        state := False
        counter := 0
        timer.reset := True
      }
    }


    val maxBitRate = p.mods.map(m => m.bitrate).max
    val outputPhy = new Area {

      val rates = p.mods.map(m => m.id -> log2Up(m.clkRate))
      val sclkWrite = Bits(p.spi.ioRate bits)
      sclkWrite := 0
      when(io.cmd.valid && io.cmd.isData){
        switch(io.config.mod) {
          for (m <- p.mods) {
            is(m.id){
              m.clkRate match {
                case 1 => sclkWrite := (default -> (fsm.state ^ io.config.kind.cpha))
                case _ => for(bitId <- 0 until p.spi.ioRate){
                  sclkWrite(bitId) := (if((bitId * m.clkRate / p.spi.ioRate) % 2 == 0) io.config.kind.cpha else !io.config.kind.cpha)
                }
              }
            }
          }
        }
      }


      io.spi.sclk.write := sclkWrite ^ B(sclkWrite.range -> io.config.kind.cpol)




      val dataWrite = Bits(maxBitRate bits)
      val widthSel = io.config.mod.muxListDc( p.mods.map(m => m.id -> U(widths.indexOf(m.bitrate), log2Up(widthMax + 1) bits)))
      dataWrite.assignDontCare()
      switch(widthSel){
        for((width, widthId) <- widths.zipWithIndex){
          is(widthId){
            dataWrite(0, width bits) := io.cmd.data.subdivideIn(width bits).reverse(fsm.counter >> log2Up(width))
          }
        }
      }


      io.spi.data.foreach(_.writeEnable := False)
      io.spi.data.foreach(_.write.assignDontCare())

      switch(io.config.mod){
        for(mod <- p.mods){
          is(mod.id) {
            when(io.cmd.valid && io.cmd.write){
              mod.writeMapping.map(_ % p.spi.dataWidth).distinct.foreach(i => io.spi.data(i).writeEnable := True)
            }

            if(mod.clkRate != 1){
              val ratio = p.spi.ioRate / mod.clkRate * (if(mod.ddr) 1 else 2)
              for((targetId, sourceId) <- mod.writeMapping.zipWithIndex){
                io.spi.data(targetId % p.spi.dataWidth).write(targetId / p.spi.dataWidth * ratio, ratio bits) := (default -> dataWrite(sourceId))
              }
            } else {
              if(mod.ddr) {
                when(!fsm.state) {
                  for ((targetId, sourceId) <- mod.writeMapping.zipWithIndex if targetId < p.spi.dataWidth) {
                    io.spi.data(targetId % p.spi.dataWidth).write := (default -> dataWrite(sourceId))
                  }
                } otherwise {
                  for ((targetId, sourceId) <- mod.writeMapping.zipWithIndex if targetId >= p.spi.dataWidth) {
                    io.spi.data(targetId % p.spi.dataWidth).write := (default -> dataWrite(sourceId))
                  }
                }
              } else {
                for ((targetId, sourceId) <- mod.writeMapping.zipWithIndex) {
                  io.spi.data(targetId % p.spi.dataWidth).write := (default -> dataWrite(sourceId))
                }
              }
            }
          }
        }
      }
    }


    val inputPhy = new Area{
      def sync[T <: Data](that : T, init : T = null) = Delay(that,2,init=init)
      val mod = sync(io.config.mod)
      val readFill = sync(fsm.readFill, False)
      val readDone = sync(fsm.readDone, False)
      val buffer = Reg(Bits(p.dataWidth - p.mods.map(_.bitrate).min bits))
      val bufferNext = Bits(p.dataWidth bits).assignDontCare().allowOverride
      val widthSel = mod.muxListDc(p.mods.map(m => m.id -> U(widths.indexOf(m.bitrate), log2Up(widthMax + 1) bits)))
      val dataWrite, dataRead = Bits(maxBitRate bits)
      val dataReadBuffer = RegNextWhen(Cat(io.spi.data.map(_.read(1))), !sync(fsm.state))
      val dataReadSource = Cat(io.spi.data.map(_.read(0))) ## dataReadBuffer

      dataRead.assignDontCare()

      switch(mod){
        for(mod <- p.mods){
          is(mod.id) {
            if(mod.clkRate != 1){
              assert(mod.clkRate == 2)
              val ratio = p.spi.ioRate / mod.clkRate
              for((sourceId, targetId) <- mod.readMapping.zipWithIndex) {
                dataRead(targetId) := io.spi.data(sourceId % p.spi.dataWidth).read(((sourceId / p.spi.dataWidth - 1) & (mod.clkRate-1))*ratio)
              }
            } else {
              for((sourceId, targetId) <- mod.readMapping.zipWithIndex) {
                dataRead(targetId) := dataReadSource(sourceId)
              }
            }
          }
        }
      }


      switch(widthSel) {
        for ((width,widthId) <- widths.zipWithIndex) {
          is(widthId) {
            bufferNext := (buffer ## dataRead(0, width bits)).resized
            when(readFill) { buffer := bufferNext.resized }
          }
        }
      }

      io.rsp.valid := readDone
      io.rsp.data := bufferNext
    }
  }
}

