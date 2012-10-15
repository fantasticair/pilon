package org.broadinstitute.pilon

import java.io.{ File, PrintWriter, FileWriter, BufferedWriter }

class Vcf(val file: File, val contigsWithSizes: List[(String, Int)] = Nil) {
  val writer = new PrintWriter(new BufferedWriter(new FileWriter(file)))
  val tab = "\t"

  def writeHeader = {
    val date = (new java.text.SimpleDateFormat("yyyyMMdd")).format(new java.util.Date())
    val ref = (new File(Pilon.genomePath)).toURI
    writer.println("##fileformat=VCFv4.1")
    writer.println("##fileDate=" + date)
    writer.println("##source=\"" + Version.version + "\"")
    writer.println("##PILON=\"" + Pilon.commandArgs.mkString(" ") + "\"")
    writer.println("##reference=" + ref)
    for ((c, s) <- contigsWithSizes)
      writer.println("##contig=<ID=" + c + ",length=" + s + ">")
    writer.println("##FILTER=<ID=LowConf,Description=\"Low Confidence Call\">")
    writer.println("##FILTER=<ID=LowCov,Description=\"Low Coverage of good reads at location\">")
    //writer.println("##FILTER=<ID=LowMQ,Description=\"Low mean mapping quality at location\">")
    writer.println("##FILTER=<ID=Amb,Description=\"Ambiguous evidence in haploid genome\">")
    writer.println("##INFO=<ID=DP,Number=1,Type=Integer,Description=\"Valid read depth; some reads may have been filtered\">")
    writer.println("##INFO=<ID=TD,Number=1,Type=Integer,Description=\"Total read depth including bad pairs\">")
    writer.println("##INFO=<ID=PC,Number=1,Type=Integer,Description=\"Physical coverage of valid inserts across locus\">")
    writer.println("##INFO=<ID=BQ,Number=1,Type=Integer,Description=\"Mean base quality at locus\">")
    writer.println("##INFO=<ID=MQ,Number=1,Type=Integer,Description=\"Mean read mapping quality at locus\">")
    writer.println("##INFO=<ID=QD,Number=1,Type=Integer,Description=\"Variant confidence/quality by depth\">")
    writer.println("##INFO=<ID=BC,Number=4,Type=Integer,Description=\"Count of As, Cs, Gs, Ts at locus\">")
    writer.println("##INFO=<ID=QP,Number=4,Type=Integer,Description=\"Percentage of As, Cs, Gs, Ts weighted by Q & MQ at locus\">")
    writer.println("##INFO=<ID=AC,Number=A,Type=Integer,Description=\"Allele count in genotypes, for each ALT allele, in the same order as listed\">")
    writer.println("##INFO=<ID=AF,Number=A,Type=Float,Description=\"Fraction of evidence in support of alternate allele(s)\">")
    writer.println("##INFO=<ID=SVTYPE,Number=1,Type=String,Description=\"Type of structural variant\">")
    writer.println("##INFO=<ID=SVLEN,Number=.,Type=String,Description=\"Difference in length between REF and ALT alleles\">")
    writer.println("##INFO=<ID=END,Number=1,Type=Integer,Description=\"End position of the variant described in this record\">")
    writer.println("##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">")
    writer.println("##FORMAT=<ID=AD,Number=.,Type=String,Description=\"Allelic depths for the ref and alt alleles in the order listed\">")
    writer.println("##FORMAT=<ID=DP,Number=1,Type=String,Description=\"Approximate read depth; some reads may have been filtered\">")
    writer.println("#CHROM	POS	ID	REF	ALT	QUAL	FILTER	INFO	FORMAT	SAMPLE")
  }

  writeHeader

  def close = writer.close

  def writeRecord(region: GenomeRegion, locus: Int, pileUp: PileUp, insertionOk: Boolean = true): Unit = {
    var loc = locus
    val i = region.index(locus)

    val bc = pileUp.baseCall
    val bcString = bc.callString(insertionOk)
    val baseDP = bc.baseSum.toInt //pileUp.baseCount.sums(bc.baseIndex)
    val altBaseDP = bc.altBaseSum.toInt //pileUp.baseCount.sums(bc.altBaseIndex)
    val depth = pileUp.depth.toInt
    val (rBase, cBase, callType, refDP, altDP) = {
      if (bc.deletion) {
        loc = locus - 1
        val rBase = region.refBase(loc)
        (rBase + bcString, rBase.toString, "1/1", depth - pileUp.deletions, pileUp.deletions)
      } else if (insertionOk && bc.insertion) {
        loc = locus - 1
        val rBase = region.refBase(loc)
        (rBase.toString, rBase + bcString, "1/1", depth - pileUp.insertions, pileUp.insertions)
      } else if (bc.homo) {
        val rBase = region.refBase(loc).toString
        if (rBase == bcString || bcString == "N")
          (rBase.toString, bcString, "0/0", baseDP, altBaseDP)
        else {
          (rBase.toString, bcString, "1/1", altBaseDP, baseDP)
        }
      } else {
        val rBase = region.refBase(loc)
        if (rBase == bc.base) {
          (rBase.toString, bc.altBase.toString, "0/1", baseDP, altBaseDP)
        } else {
          (rBase.toString, bc.base.toString, "0/1", altBaseDP, baseDP)
        }
      }
    }
    var filters = List[String]()
    if (depth < region.minDepth) filters ::= "LowCov"
    if (!bc.highConfidence && !bc.indel) filters ::= "LowConf"
    if (!Pilon.diploid && !bc.homo && !bc.indel) filters ::= "Amb"
    if (filters.isEmpty) filters ::= "PASS"
    val cBaseVcf = if (cBase == "N" || cBase == rBase) "." else cBase
    var line = region.name + tab + loc + tab + "." + tab + rBase + tab + cBaseVcf
    line += tab + (if (bc.deletion) "." else bc.score.toString)
    val filter = filters.mkString(";")
    line += tab + filter

    val ac = callType match {
      case "0/0" => 0
      case "0/1" => 1
      case "1/1" => 2
    }
    var info = "DP=" + pileUp.depth
    info += ";TD=" + (pileUp.depth + pileUp.badPair)
    info += ";BQ=" + pileUp.meanQual
    info += ";MQ=" + pileUp.meanMq
    info += ";QD=" + bc.q
    info += ";BC=" + pileUp.baseCount
    info += ";QP=" + pileUp.qualSum.toStringPct
    info += ";PC=" + pileUp.physCov
    info += ";AC=" + ac
    val af = if (refDP + altDP > 0 && cBaseVcf != ".")
      (altDP.toFloat / (refDP + altDP).toFloat)
    else 0.0
    info += ";AF=" + ("%.2f".format(af))
    line += tab + info

    var gt = "GT"
    var gtInfo = callType
    //AD removed
    //if (ac > 0) {
    //  gt += ":AD"
    //  gtInfo += ":" + refDP + "," + altDP 
    //}
    line += tab + gt + tab + gtInfo
    writer.println(line)
    if (insertionOk && bc.insertion) writeRecord(region, locus, pileUp, false)
  }

  def writeFixRecord(region: GenomeRegion, fix: GenomeRegion.Fix) = {
    val loc = fix._1 - 1
    val rBase = region.refBase(loc)
    val ref = rBase + fix._2
    val alt = rBase + fix._3
    val svlen = alt.length - ref.length
    val svend = loc + ref.length - 1
    val svtype = if (svlen < 0) "DEL" else "INS"
    var line = region.name + tab + loc + tab + "." + tab
    line += ref + tab + alt + tab + "." + tab + "PASS" + tab
    line += "SVTYPE=" + svtype + ";SVLEN=" + svlen + ";END=" + svend + tab
    line += "GT" + tab + "1/1"
    writer.println(line)
  }
}