/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web
package services

import model.JsNodeId
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.LDAPConstants
import com.unboundid.ldap.sdk.DN
import com.normation.rudder.web.components.DateFormaterService
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE.{JsRaw, JsVar, JsArray, Str}
import net.liftweb.http.SHtml._
import com.normation.exceptions.TechnicalException
import net.liftweb.http.Templates
import org.joda.time.DateTime

/**
 * A service used to display details about a server 
 * inventory with tabs like:
 * [general][software][network][file system]
 * 
 * Use it by calling: 
 * # head if not yet called in that page
 * # show(serverId) : NodeSeq 
 *    where you want to display node information
 * # jsInit(serverId) : Cmd
 *    to init javascript for it 
 */
object DisplayServer {
  
  private val getSoftwareService = inject[ReadOnlySoftwareDAO]
  
  private val templatePath = List("templates-hidden", "server_details_tabs")
  private def template() =  Templates(templatePath) match {
    case Empty | Failure(_,_,_) => 
      throw new TechnicalException("Template for server details not found. I was looking for %s.html".format(templatePath.mkString("/")))
    case Full(n) => n
  }
  
  private def content() = chooseTemplate("serverdetails","content",template)
  
  private def loadSoftware(jsId:JsNodeId, softIds:Seq[SoftwareUuid])(nodeId:String):JsCmd = {
    //id is not used anymore ?
    (for {
      seq <- getSoftwareService.getSoftware(softIds)
      val gridDataId = htmlId(jsId,"soft_grid_data_")
      val gridId = htmlId(jsId,"soft_grid_")
    } yield SetExp(JsVar(gridDataId),JsArray(seq.map { x => JsArray(
        Str(x.name.getOrElse("")),
        Str(x.version.map(_.value).getOrElse("")),
        Str(x.description.getOrElse(""))
      )}:_*) ) & JsRaw("""$('#%s').dataTable({"aaData":%s,"bJQueryUI": false, "bPaginate": true, "bLengthChange": false, "bAutoWidth": false, "aoColumns": [ {"sWidth": "200px"},{"sWidth": "150px"},{"sWidth": "350px"}] });moveFilterAndPaginateArea('#%s');""".format(gridId,gridDataId,gridId))
    ) match {
      case Empty => Alert("No software found for that server")
      case Failure(m,_,_) => Alert("Error when trying to fetch software. Reported message: "+m)
      case Full(js) => js
    }
  }
  
  def head() = chooseTemplate("serverdetails","head",template)
  
  def jsInit(nodeId:NodeId, softIds:Seq[SoftwareUuid], salt:String="", tabContainer : Option[String] = None):JsCmd = {
    val jsId = JsNodeId(nodeId,salt)
    val detailsId = htmlId(jsId,"details_")
    val softGridDataId = htmlId(jsId,"soft_grid_data_")
    val softGridId = htmlId(jsId,"soft_grid_")
    val softPanelId = htmlId(jsId,"sd_soft_")
    val eltIds = List("fs", "net","bios", "controllers", "memories", "ports", "processors", "slots", "sounds", "storages", "videos").
      map(x => htmlId(jsId, x+ "_grid_"))
      
    JsRaw("var "+softGridDataId +"= null") & 
    OnLoad(
      JsRaw("$('#"+detailsId+"').tabs()") & 
      { eltIds.map { i => 
          JsRaw("""$('#%s').dataTable({"bJQueryUI": false,"bFilter": false,"asStripClasses": [ 'color1', 'color2' ],"bPaginate": false, "bInfo":false});
              | """.stripMargin('|').format(i,i)):JsCmd
        }.reduceLeft( (i,acc) => acc & i )
      } &
      JsRaw("roundTabs()") &
      // for the software tab, we check for the panel id, and the firstChild id
      // if the firstChild.id == softGridId, then it hasn't been loaded, otherwise it is softGridId_wrapper
      JsRaw("""
          | $("#%s").bind( "tabsshow", function(event, ui) {
          |   if(ui.panel.id== '%s' && ui.panel.firstChild.id == '%s') { %s; }
          | });
          """.stripMargin('|').format(tabContainer.getOrElse(detailsId),
            softPanelId,softGridId, 
            SHtml.ajaxCall(JsRaw("'"+nodeId.value+"'"), loadSoftware(jsId, softIds) )._2.toJsCmd)
      )
    )
  }
    
  /**
   * Show details about the server in a tabed fashion if
   * the server exists, display an error message if the 
   * server is not found or if a problem occurred when fetching it
   * 
   * showExtraFields : if true, then everything is shown, otherwise, the extrafileds are not in the main tabs.
   * To show then, look at showExtraHeader
   * 
   * Salt is a string added to every used id. 
   * It useful only if you have several DisplayServer element on a single page. 
   */
  def show(sm:FullInventory, showExtraFields : Boolean = true, salt:String = "") : NodeSeq = {
    val jsId = JsNodeId(sm.node.main.id,salt)
    val mainTabDeclaration : List[NodeSeq] =
      { if (showExtraFields)  <li><a href={htmlId_#(jsId,"sd_fs_")}>File systems</a></li>  else NodeSeq.Empty } ::
      { if (showExtraFields)  <li><a href={htmlId_#(jsId,"sd_net_")}>Network interfaces</a></li>  else NodeSeq.Empty } ::
      { if (showExtraFields) <li><a href={htmlId_#(jsId,"sd_soft_")}>Software</a></li>  else NodeSeq.Empty } ::
      // 
      <li><a href={htmlId_#(jsId,"sd_bios_")}>Bios</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_controllers_")}>Controllers</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_memories_")}>Memories</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_ports_")}>Ports</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_processors_")}>Processors</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_slots_")}>Slots</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_sounds_")}>Sounds</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_storages_")}>Storages</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_videos_")}>Videos</a></li> ::
      Nil
    
    val tabContent = 
      { if (showExtraFields) displayTabFilesystems(jsId, sm) else Nil } ::
      { if (showExtraFields) displayTabNetworks(jsId, sm) else Nil } ::
      { if (showExtraFields) displayTabSoftware(jsId) else Nil } ::
    //  displayTabSoftware(jsId) ::
      displayTabBios(jsId, sm) ::
      displayTabControllers(jsId, sm) ::
      displayTabMemories(jsId, sm) ::
      displayTabPorts(jsId, sm) ::
      displayTabProcessors(jsId, sm) ::
      displayTabSlots(jsId, sm) ::
      displayTabSounds(jsId, sm) ::
      displayTabStorages(jsId, sm) ::
      displayTabVideos(jsId, sm) ::
      Nil
    
      <div id={htmlId(jsId,"details_")} class="sInventory">{bind("server", content,
        "tabsDefinition" -> <ul>{mainTabDeclaration}</ul>,
        "grid_tabs" -> tabContent.flatten
    )}</div> 
  }

  /**
   * show the extra tabs header part
   */
  def showExtraHeader(sm:FullInventory, salt:String = "") : NodeSeq = {    
    val jsId = JsNodeId(sm.node.main.id,salt)
    <xml:group>
    <li><a href={htmlId_#(jsId,"sd_fs_")}>File systems</a></li>
    <li><a href={htmlId_#(jsId,"sd_net_")}>Network interfaces</a></li>
    <li><a href={htmlId_#(jsId,"sd_soft_")}>Software</a></li>
    </xml:group>
  }

  /**
   * show the extra part
   */
  def showExtraContent(sm:FullInventory, salt:String = "") : NodeSeq = {
    val jsId = JsNodeId(sm.node.main.id,salt)
    displayTabFilesystems(jsId, sm) ++
    displayTabNetworks(jsId, sm) ++
    displayTabSoftware(jsId)
  }
  
  /**
   * Show the details in a panned version, with Node Summary, Inventory, Network, Software
   * Should be used with jsInit(dn:String, softIds:Seq[SoftwareUuid], salt:String="", tabContainer = Some("node_tabs"))
   */
  def showPannedContent(sm:FullInventory, salt:String = "") : NodeSeq = {
    val jsId = JsNodeId(sm.node.main.id,salt)
    <div id="node_tabs" class="tabs">
      <ul>
        <li><a href={htmlId_#(jsId,"node_summary_")}>Node summary</a></li>
        <li><a href={htmlId_#(jsId,"node_inventory_")}>Hardware</a></li>
        {showExtraHeader(sm, salt)}
       </ul>
       <div id="node_inventory">
         <div id={htmlId(jsId,"node_inventory_")}>
           {show(sm, false, "")}
         </div>
       </div>
       {showExtraContent(sm, salt)}
       
       <div id={htmlId(jsId,"node_summary_")}>
         {showNodeDetails(sm, None, salt)}
       </div>
    </div>
  }
  
  // mimic the content of server_details/ShowServerDetailsFromNode
  def showNodeDetails(sm:FullInventory, creationDate:Option[DateTime], salt:String = "") : NodeSeq = {
    val jsId = JsNodeId(sm.node.main.id,salt)
    
    <fieldset class="nodeIndernal"><legend>Node characteristics</legend>

      <h4 style="margin-top:5px">General</h4>
        <div style="padding-left:15px;padding-top:5px">
          <b>Hostname:</b> {sm.node.main.hostname}<br/>
          <b>Machine type:</b> {displayMachineType(sm.machine)}<br/>
          <b>Total physical memory (RAM):</b> {sm.node.ram.map( _.toStringMo).getOrElse("-")}<br/>
          <b>Total swap space:</b> {sm.node.swap.map( _.toStringMo).getOrElse("-")}<br/>
        </div>
            
      <h4 style="margin-top:5px">Operating system details</h4>
        <div style="padding-left:15px;padding-top:5px">
          <b>Operating System:</b> {sm.node.main.osDetails.fullName}<br/>
          <b>Operating System Type:</b> {sm.node.main.osDetails.os.kernelName}<br/>
          <b>Operating System Name:</b> {S.?("os.name."+sm.node.main.osDetails.os.name)}<br/>
          <b>Operating System Version:</b> {sm.node.main.osDetails.version.value}<br/>
          <b>Operating System Service Pack:</b> {sm.node.main.osDetails.servicePack.getOrElse("None")}<br/>
        </div>
            
      <h4 style="margin-top:5px">Rudder information</h4>
        <div style="padding-left:15px;padding-top:5px">
          <b>Agent name:</b> {sm.node.agentNames.map(_.toString).mkString(";")}<br/>
          <b>Rudder ID:</b> {sm.node.main.id.value}<br/>
          <b>Date inventory last received:</b>  {sm.node.inventoryDate.map(DateFormaterService.getFormatedDate(_)).getOrElse("Unknown")}<br/>
          {creationDate.map { creation =>
            <xml:group><b>Date first accepted in Rudder:</b> {DateFormaterService.getFormatedDate(creation)}<br/></xml:group>
          }.getOrElse(NodeSeq.Empty) }
        </div>
        
      <h4 style="margin-top:5px">Accounts</h4>
        <div style="padding-left:15px;padding-top:5px">
          <b>Administrator account:</b> {sm.node.main.rootUser}<br/>
          <b>Local account(s):</b> {displayAccounts(sm.node)}<br/>
        </div>
    </fieldset>
  }
  
  private def htmlId(jsId:JsNodeId, prefix:String="") : String = prefix + jsId.toString
  private def htmlId_#(jsId:JsNodeId, prefix:String="") : String = "#" + prefix + jsId.toString
  
  private def ?(in:Option[String]) : NodeSeq = in.map(Text(_)).getOrElse(NodeSeq.Empty)

  
  private def displayMachineType(opt:Option[MachineInventory]) : NodeSeq = {
    opt match {
      case None => NodeSeq.Empty
      case Some(machine) => ( 
        machine.machineType match {
          case PhysicalMachineType => Text("Physical machine")
          case VirtualMachineType(vmType) => Text("Virtual machine (%s)".format(S.?("vm.type." + vmType.name)))
        }
      )
    }
  }
  
  private def displayPublicKeys(node:NodeInventory) : NodeSeq = <b>Public Key(s): </b> ++ {if(node.publicKeys.isEmpty) {
          Text("None")
        } else <ul>{node.publicKeys.zipWithIndex.flatMap{ case (x,i) => (<b>{"[" + i + "] "}</b> ++ {Text(x.key.grouped(65).toList.mkString("\n"))})}}</ul> }
  
  private def displayNodeInventoryInfo(node:NodeInventory) : NodeSeq = {
    val details : NodeSeq = node.main.osDetails match {
      case Linux(os, osFullName, osVersion, osServicePack, kernelVersion) => //display kernelVersion, distribution, distributionVersion
        (<li><b>Distribution (version): </b> {os.name} ({osVersion.value})</li>
        <li><b>Kernel version: </b> {kernelVersion.value}</li>
        <li><b>Service Pack: </b> {?(osServicePack)}</li>)
      case Windows(os, osFullName, osVersion, osServicePack, kernelVersion, domain, company, key, id) => 
        (<li><b>Version:</b>: {osVersion.value}</li>
        <li><b>Kernel version: </b> {kernelVersion.value}</li>
        <li><b>Service Pack: </b> {?(osServicePack)}</li>
        <li><b>User Domain:</b> {domain}</li>
        <li><b>Company:</b> {company}</li>
        <li><b>Id:</b> {id}</li>
        <li><b>Key:</b> {key}</li>)
      case _ => NodeSeq.Empty
    }
    <li><b>Complete name: </b> {node.main.osDetails.fullName}</li> ++
    details
  }
  
  //show a comma separated list with description in tooltip 
  private def displayPolicies(node:NodeInventory) : NodeSeq = {
    <b>Applied policies: </b> ++ {Text{if(node.policies.isEmpty) {
        "None"
      } else {
        node.policies.mkString(", ")
      }}
    }
  }
  
  private def displayAccounts(node:NodeInventory) : NodeSeq = {
    Text{if(node.accounts.isEmpty) {
        "None"
      } else {
        node.accounts.sortWith(_ < _).mkString(", ")
      }
    }
  }

  private def displayTabGrid[T](jsId:JsNodeId)(eltName:String, optSeq:Box[Seq[T]])(columns:List[(String, T => NodeSeq)]) = {

    <div id={htmlId(jsId,"sd_"+eltName +"_")} class="sInventory">{
      optSeq match {
        case Empty => <span>No matching components detected on this node</span>
        case Failure(m,_,_) => <span class="error">Error when trying to fetch file systems. Reported message: {m}</span>
        case Full(seq) if (seq.isEmpty && eltName != "soft") => <span>No matching components detected on this node</span>
        case Full(seq) => 
          <table cellspacing="0" id={htmlId(jsId,eltName + "_grid_")} style="width:100%;">
          <thead>
            <tr class="head">{
              columns.map {h => <th>{h._1}</th> }.toSeq
            }</tr>
          </thead>
          <tbody>{ seq.flatMap { x =>
            <tr>{ columns.flatMap{ case(header,renderLine) =>  <td>{renderLine(x)}</td> } }</tr>
          } }</tbody>
          </table>
      }
    }<div id={htmlId(jsId,eltName + "_grid_") + "_paginate_area"} class="paginate"/>
    </div>
  }
  
  
  private def displayTabSoftware(jsId:JsNodeId) : NodeSeq = 
    displayTabGrid(jsId)("soft", 
        //do not retrieve software here
        //getNodeInventoryService.getSoftware(id)
        Full(Seq())
    ){
      ("Name", {x:Software => ?(x.name)} ) :: 
      ("Version", {x:Software => ?(x.version.map(_.value)) } ) :: 
      ("Description", {x:Software => ?(x.description) } ) :: 
      Nil
    }

  private def displayTabNetworks(jsId:JsNodeId,sm:FullInventory) : NodeSeq = 
    displayTabGrid(jsId)("net", Full(sm.node.networks)){
        ("Interface", {x:Network => Text(x.name)}) :: 
        ("IP address", {x:Network => Text(x.ifAddresses.map{ _.getHostAddress }.mkString(", "))}) :: 
        ("Mask", {x:Network => Text(x.ifMask.map{ _.getHostAddress }.mkString(", "))}) ::
        ("DHCP server", {x:Network => Text(x.ifDhcp.map{ _.getHostAddress }.mkString(", "))}) ::
        ("MAC address", {x:Network => ?(x.macAddress)}) :: 
        //("Gateway", {x:Network => Text(x.ifGateway.map{ _.getHostAddress }.mkString(", "))}) :: 
        //("Network", {x:Network => Text(x.ifSubnet.map{ _.getHostAddress }.mkString(", "))}) ::
        ("Type", {x:Network => ?(x.ifType)}) ::
        //("Type-MIB", {x:Network => ?(x.typeMib)}) ::
        ("Speed", {x:Network => ?(x.speed)}) ::
        ("Status", {x:Network => ?(x.status)}) ::
        Nil
    }
  
  private def displayTabFilesystems(jsId:JsNodeId,sm:FullInventory) : NodeSeq = 
    displayTabGrid(jsId)("fs", Full(sm.node.fileSystems)){
        ("Mount point", {x:FileSystem => Text(x.mountPoint)}) ::
        ("Filesystem", {x:FileSystem => ?(x.name)}) ::
        ("Free space", {x:FileSystem => ?(x.freeSpace.map(_.toStringMo))}) ::
        ("Total space", {x:FileSystem => ?(x.totalSpace.map(_.toStringMo))}) ::
        ("File count", {x:FileSystem => ?(x.fileCount.map(_.toString))}) :: 
        Nil
    }
    
  private def displayTabBios(jsId:JsNodeId,sm:FullInventory) : NodeSeq = 
    displayTabGrid(jsId)("bios", sm.machine.map(fm => fm.bios)){
        ("Name", {x:Bios => Text(x.name)}) ::
        ("Editor", {x:Bios => ?(x.editor.map( _.name))}) :: 
        ("Version", {x:Bios => ?(x.editor.map( _.name))}) :: 
        ("Release Date", {x:Bios => ?(x.releaseDate.map(DateFormaterService.getFormatedDate(_)))}) :: 
        Nil
    }

  private def displayTabControllers(jsId:JsNodeId,sm:FullInventory) : NodeSeq = 
    displayTabGrid(jsId)("controllers", sm.machine.map(fm => fm.controllers)){
        ("Name", {x:Controller => Text(x.name)}) ::
        ("Manufacturer", {x:Controller => ?(x.manufacturer.map( _.name))}) ::
        ("Type", {x:Controller => ?(x.cType)}) ::
        ("Quantity", {x:Controller => Text(x.quantity.toString)}) :: 
        Nil
    }
  
  private def displayTabMemories(jsId:JsNodeId,sm:FullInventory) : NodeSeq = 
    displayTabGrid(jsId)("memories", sm.machine.map(fm => fm.memories)){
        ("Slot", {x:MemorySlot => Text(x.slotNumber)}) ::
        ("Capacity", {x:MemorySlot => ?(x.capacity.map( _.toStringMo ))}) ::
        ("Description", {x:MemorySlot => ?(x.description)}) ::
        ("Serial Number", {x:MemorySlot => ?(x.serialNumber)}) ::
        ("Speed", {x:MemorySlot => ?(x.speed)}) ::
        ("Type", {x:MemorySlot => ?(x.memType)}) ::
        ("Quantity", {x:MemorySlot => Text(x.quantity.toString)}) ::
        Nil
    }
    
  private def displayTabPorts(jsId:JsNodeId,sm:FullInventory) : NodeSeq = 
    displayTabGrid(jsId)("ports", sm.machine.map(fm => fm.ports)){
        ("Name", {x:Port => Text(x.name)}) ::
        ("Type", {x:Port => ?(x.pType )}) ::
        ("Description", {x:Port => ?(x.description)}) :: 
        ("Quantity", {x:Port => Text(x.quantity.toString)}) ::
        Nil
    }
    
  private def displayTabProcessors(jsId:JsNodeId,sm:FullInventory) : NodeSeq = 
    displayTabGrid(jsId)("processors", sm.machine.map(fm => fm.processors)){
        ("Name", {x:Processor => Text(x.name)}) ::
        ("Speed", {x:Processor => ?(x.speed.map(_.toString))}) :: 
        ("Model", {x:Processor => ?(x.model)}) :: 
        ("Family", {x:Processor => ?(x.family)}) :: 
        ("Manufacturer", {x:Processor => ?(x.manufacturer.map(_.name))}) :: 
        ("Stepping", {x:Processor => ?(x.stepping.map(_.toString))}) :: 
        ("Quantity", {x:Processor => Text(x.quantity.toString)}) :: 
        Nil
    }
  
  private def displayTabSlots(jsId:JsNodeId,sm:FullInventory) : NodeSeq = 
    displayTabGrid(jsId)("slots", sm.machine.map(fm => fm.slots)){
        ("Name" , {x:Slot => Text(x.name)}) :: 
        ( "Description" , {x:Slot => ?(x.description)}) :: 
        ( "Status" , {x:Slot => ?(x.status)}) :: 
        ( "Quantity" , {x:Slot => Text(x.quantity.toString)}) :: 
        Nil
    }
  
  private def displayTabSounds(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("sounds", sm.machine.map(fm => fm.sounds)){
        ("Name" , {x:Sound => Text(x.name)}) :: 
        ( "Description" , {x:Sound => ?(x.description)}) :: 
        ( "Quantity" , {x:Sound => Text(x.quantity.toString)}) :: 
        Nil
    }
  
  private def displayTabStorages(jsId:JsNodeId,sm:FullInventory) : NodeSeq = 
    displayTabGrid(jsId)("storages", sm.machine.map(fm => fm.storages)){
        ( "Name" , {x:Storage => Text(x.name)}) :: 
        ( "Description" , {x:Storage => ?(x.description)}) :: 
        ( "Size" , {x:Storage => ?(x.size.map( _.toStringMo))}) :: 
        ( "Firmware" , {x:Storage => ?(x.firmware)}) :: 
        ( "Manufacturer" , {x:Storage => ?(x.manufacturer.map(_.name))}) :: 
        ( "Model" , {x:Storage => ?(x.model)}) :: 
        ( "Serial" , {x:Storage => ?(x.serialNumber)}) :: 
        ( "Type" , {x:Storage => ?(x.sType)}) :: 
        ( "Quantity" , {x:Storage => Text(x.quantity.toString)}) :: 
        Nil
    }
  
  private def displayTabVideos(jsId:JsNodeId,sm:FullInventory) : NodeSeq = 
    displayTabGrid(jsId)("videos", sm.machine.map(fm => fm.videos)){
        ("Name" , {x:Video => Text(x.name)}) :: 
        ( "Chipset" , {x:Video => ?(x.chipset)}) :: 
        ( "Memory" , {x:Video => ?(x.memory.map( _.toStringMo))}) :: 
        ( "Resolution" , {x:Video => ?(x.resolution)}) :: 
        ( "Quantity" , {x:Video => Text(x.quantity.toString)}) :: 
        Nil
    }    
}