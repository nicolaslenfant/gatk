package org.broadinstitute.hellbender.tools;

import htsjdk.variant.variantcontext.*;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder;
import htsjdk.variant.vcf.*;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.VariantProgramGroup;
import org.broadinstitute.hellbender.engine.FeatureContext;
import org.broadinstitute.hellbender.engine.ReadsContext;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.engine.VariantWalker;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.tools.spark.sv.utils.GATKSVVCFConstants;
import org.broadinstitute.hellbender.utils.IntervalUtils;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.fasta.CachingIndexedFastaSequenceFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@CommandLineProgramProperties(
        summary = "Normalize 10x SV VCF",
        oneLineSummary = "Normalize 10x SV VCF",
        programGroup = VariantProgramGroup.class)
public class NormalizeTenxSVVCF extends VariantWalker {

    @Argument(
            doc = "Output Somatic SNP/Indel VCF file with additional annotations.",
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME)
    protected File outputFile;

    private VariantContextWriter vcfWriter;
    private String sample;

    private List<VariantContext> variants = new ArrayList<>();
    private CachingIndexedFastaSequenceFile referenceReader;

    @Override
    public boolean requiresReference() {
        return true;
    }

    @Override
    public void onTraversalStart() {
        final VCFHeader headerForVariants = getHeaderForVariants();
        final VCFHeader newHeader = new VCFHeader(headerForVariants);
        newHeader.addMetaDataLine(VCFStandardHeaderLines.getInfoLine(VCFConstants.END_KEY));
        newHeader.addMetaDataLine(new VCFFormatHeaderLine("Qual", 1, VCFHeaderLineType.Integer, "The quality of the call (number of supporting barcodes)"));
        newHeader.addMetaDataLine(new VCFFormatHeaderLine("PS", 1, VCFHeaderLineType.Integer, "Phase set for the breakend"));
        newHeader.addMetaDataLine(new VCFFormatHeaderLine("Pairs", 1, VCFHeaderLineType.Integer, "Supporting pairs"));
        newHeader.addMetaDataLine(new VCFFormatHeaderLine("Split", 1, VCFHeaderLineType.Integer, "Supporting split reads"));
        newHeader.addMetaDataLine(new VCFFormatHeaderLine("FT", VCFHeaderLineCount.UNBOUNDED, VCFHeaderLineType.String, "Filters"));

        vcfWriter = new VariantContextWriterBuilder().setOutputFile(outputFile).build();
        final List<String> genotypeSamples = headerForVariants.getGenotypeSamples();
        Utils.validate(genotypeSamples.size() == 1, "This tool requires a single-sample 10x Long Ranger SV VCF");
        sample = genotypeSamples.get(0);
        newHeader.setSequenceDictionary(getReferenceDictionary());
        vcfWriter.setHeader(newHeader);
        vcfWriter.writeHeader(newHeader);

        try {
            referenceReader = new CachingIndexedFastaSequenceFile(referenceArguments.getReferenceFile());
        } catch (FileNotFoundException e) {
            throw new GATKException("Could not create reference reader");
        }
    }

    @Override
    public void apply(final VariantContext variant, final ReadsContext readsContext, final ReferenceContext referenceContext, final FeatureContext featureContext) {
        final Genotype genotype = variant.getGenotype(sample);
        final List<Allele> originalAlleles = variant.getAlleles();
        variant.getAlternateAllele(0).getDisplayString();
        String originalVariantId = variant.getID();
        if (variant.getAttribute(GATKSVVCFConstants.SVTYPE).equals(GATKSVVCFConstants.BREAKEND_STR)) {
            String[] originalVariantIdFields = originalVariantId.split("_");
            final String callSuffix = originalVariantIdFields[originalVariantIdFields.length - 1];
            final String id = originalVariantId + "_" + sample + "_BND_" + variant.getAttribute("SVTYPE2") + "_" + callSuffix;
            final VariantContextBuilder builder = new VariantContextBuilder(variant);
            final List<Allele> alleles = new ArrayList<>(variant.getAlleles().size());
            alleles.add(Allele.create(referenceContext.getBase(), true));
            alleles.addAll(variant.getAlternateAlleles());

            final GenotypeBuilder genotypeBuilder =
                    new GenotypeBuilder(sample)
                            .alleles(Arrays.asList(alleles.get(originalAlleles.indexOf(genotype.getAllele(0))),
                                    alleles.get(originalAlleles.indexOf(genotype.getAllele(1)))))
                            .attribute("Qual", variant.getPhredScaledQual())
                            .attribute("PS", variant.getAttribute("PS"))
                            .attribute("Pairs", variant.getAttribute("Pairs"))
                            .attribute("Split", variant.getAttribute("Split"))
                            .attribute("FT", variant.getFilters());


            final VariantContext vc = builder
                    .id(id)
                    .attributes(variant.getAttributes())
                    .alleles(alleles)
                    .genotypes(genotypeBuilder.make())
                    .make();

            variants.add(vc);
        } else if (variant.getAttribute(GATKSVVCFConstants.SVTYPE).equals(GATKSVVCFConstants.SYMB_ALT_ALLELE_DEL)) {
            final String id = originalVariantId + "_" + sample + "_DEL";
            final int end = variant.getEnd();
            final byte baseAtPos = referenceContext.getBase();
            final byte baseAtEnd = referenceReader.getSubsequenceAt(variant.getContig(), variant.getEnd(), variant.getEnd()).getBases()[0];


            final List<Allele> alleles1 = new ArrayList<>(2);
            alleles1.add(Allele.create(baseAtPos, true));
            alleles1.add(Allele.create((char) baseAtPos + "[" + variant.getContig() + ":" + end + "["));

            final VariantContext bnd1 = new VariantContextBuilder(variant)
                    .id(id + "_1")
                    .alleles(alleles1)
                    .log10PError(variant.getLog10PError())
                    .attribute(GATKSVVCFConstants.SVTYPE, "BND")
                    .attribute("SVTYPE2", "DEL")
                    .rmAttribute("END")
                    .rmAttribute("SVLEN")
                    .rmAttribute("CIEND")
                    .genotypes(
                            new GenotypeBuilder(sample)
                                    .alleles(Arrays.asList(alleles1.get(originalAlleles.indexOf(genotype.getAllele(0))),
                                                            alleles1.get(originalAlleles.indexOf(genotype.getAllele(1)))))
                                    .phased(genotype.isPhased())
                                    .attribute("Qual", variant.getPhredScaledQual())
                                    .attribute("PS", variant.getAttribute("PS"))
                                    .attribute("Pairs", variant.getAttribute("PAIRS"))
                                    .attribute("Split", variant.getAttribute("SPLIT"))
                                    .filters(new ArrayList<>(variant.getFilters())).make())
                    .make();

            final List<Allele> alleles2 = new ArrayList<>(2);
            alleles2.add(Allele.create(baseAtEnd, true));
            alleles2.add(Allele.create("]" + variant.getContig() + ":" + variant.getStart() + "]" + (char) baseAtEnd));

            final VariantContext bnd2 = new VariantContextBuilder(variant)
                    .id(id + "_2")
                    .start(end)
                    .alleles(alleles2)
                    .log10PError(variant.getLog10PError())
                    .attribute(GATKSVVCFConstants.SVTYPE, "BND")
                    .attribute("SVTYPE2", "DEL")
                    .rmAttribute("END")
                    .rmAttribute("SVLEN")
                    .rmAttribute("CIEND")
                    .attribute("CIPOS", variant.getAttribute("CIEND"))
                    .genotypes(
                            new GenotypeBuilder(genotype)
                                    .alleles(Arrays.asList(alleles2.get(originalAlleles.indexOf(genotype.getAllele(0))),
                                            alleles2.get(originalAlleles.indexOf(genotype.getAllele(1)))))
                                    .phased(genotype.isPhased())
                                    .attribute("Qual", variant.getPhredScaledQual())
                                    .attribute("PS", variant.getAttribute("PS"))
                                    .attribute("Pairs", variant.getAttribute("PAIRS"))
                                    .attribute("Split", variant.getAttribute("SPLIT"))
                                    .filters(new ArrayList<>(variant.getFilters())).make())
                    .make();

            variants.add(bnd1);
            variants.add(bnd2);
        } else if (variant.getAttribute(GATKSVVCFConstants.SVTYPE).equals(GATKSVVCFConstants.SYMB_ALT_ALLELE_DUP)) {
            final String id = originalVariantId + "_" + sample + "_DUP";
            final int end = variant.getEnd();
            final byte baseAtPos = referenceContext.getBase();
            final byte baseAtEnd = referenceReader.getSubsequenceAt(variant.getContig(), variant.getEnd(), variant.getEnd()).getBases()[0];


            final List<Allele> alleles1 = new ArrayList<>(2);
            alleles1.add(Allele.create(baseAtPos, true));
            alleles1.add(Allele.create("]" + variant.getContig() + ":" + variant.getEnd() + "]" + (char) baseAtPos));


            final VariantContext bnd1 = new VariantContextBuilder(variant)
                    .id(id + "_1")
                    .alleles(alleles1)
                    .log10PError(variant.getLog10PError())
                    .attribute(GATKSVVCFConstants.SVTYPE, "BND")
                    .attribute("SVTYPE2", "DUP")
                    .rmAttribute("END")
                    .rmAttribute("SVLEN")
                    .rmAttribute("CIEND")
                    .genotypes(
                            new GenotypeBuilder(sample)
                                    .alleles(Arrays.asList(alleles1.get(originalAlleles.indexOf(genotype.getAllele(0))),
                                            alleles1.get(originalAlleles.indexOf(genotype.getAllele(1)))))
                                    .phased(genotype.isPhased())
                                    .attribute("Qual", variant.getPhredScaledQual())
                                    .attribute("PS", variant.getAttribute("PS"))
                                    .attribute("Pairs", variant.getAttribute("PAIRS"))
                                    .attribute("Split", variant.getAttribute("SPLIT"))
                                    .filters(new ArrayList<>(variant.getFilters())).make())
                    .make();

            final List<Allele> alleles2 = new ArrayList<>(2);
            alleles2.add(Allele.create(baseAtEnd, true));
            alleles2.add(Allele.create((char) baseAtEnd + "[" + variant.getContig() + ":" + variant.getStart() + "["));

            final VariantContext bnd2 = new VariantContextBuilder(variant)
                    .id(id + "_2")
                    .start(end)
                    .alleles(alleles2)
                    .log10PError(variant.getLog10PError())
                    .attribute(GATKSVVCFConstants.SVTYPE, "BND")
                    .attribute("SVTYPE2", "DUP")
                    .rmAttribute("END")
                    .rmAttribute("SVLEN")
                    .rmAttribute("CIEND")
                    .attribute("CIPOS", variant.getAttribute("CIEND"))
                    .genotypes(
                            new GenotypeBuilder(genotype)
                                    .alleles(Arrays.asList(alleles2.get(originalAlleles.indexOf(genotype.getAllele(0))),
                                            alleles2.get(originalAlleles.indexOf(genotype.getAllele(1)))))
                                    .phased(genotype.isPhased())
                                    .attribute("Qual", variant.getPhredScaledQual())
                                    .attribute("PS", variant.getAttribute("PS"))
                                    .attribute("Pairs", variant.getAttribute("PAIRS"))
                                    .attribute("Split", variant.getAttribute("SPLIT"))
                                    .filters(new ArrayList<>(variant.getFilters())).make())
                    .make();

            variants.add(bnd1);
            variants.add(bnd2);
        } else if (variant.getAttribute(GATKSVVCFConstants.SVTYPE).equals(GATKSVVCFConstants.SYMB_ALT_ALLELE_INV)) {
            final String id = originalVariantId + "_" + sample + "_INV";
            final int end = variant.getEnd();
            final byte baseAtPos = referenceContext.getBase();
            final byte baseAfterPos = referenceReader.getSubsequenceAt(variant.getContig(), variant.getStart() + 1, variant.getStart() + 1).getBases()[0];
            final byte baseAtEnd = referenceReader.getSubsequenceAt(variant.getContig(), variant.getEnd(), variant.getEnd()).getBases()[0];
            final byte baseAfterEnd = referenceReader.getSubsequenceAt(variant.getContig(), variant.getEnd() + 1, variant.getEnd() + 1).getBases()[0];


            final List<Allele> alleles1 = new ArrayList<>(2);
            alleles1.add(Allele.create(baseAtPos, true));
            alleles1.add(Allele.create((char) baseAtPos + "]" + variant.getContig() + ":" + variant.getEnd() + "]"));

            final VariantContext bnd1 = new VariantContextBuilder(variant)
                    .id(id + "_1")
                    .alleles(alleles1)
                    .log10PError(variant.getLog10PError())
                    .attribute(GATKSVVCFConstants.SVTYPE, "BND")
                    .attribute("SVTYPE2", "INV")
                    .rmAttribute("END")
                    .rmAttribute("SVLEN")
                    .rmAttribute("CIEND")
                    .genotypes(
                            new GenotypeBuilder(sample)
                                    .alleles(Arrays.asList(alleles1.get(originalAlleles.indexOf(genotype.getAllele(0))),
                                            alleles1.get(originalAlleles.indexOf(genotype.getAllele(1)))))
                                    .phased(genotype.isPhased())
                                    .attribute("Qual", variant.getPhredScaledQual())
                                    .attribute("PS", variant.getAttribute("PS"))
                                    .attribute("Pairs", variant.getAttribute("PAIRS"))
                                    .attribute("Split", variant.getAttribute("SPLIT"))
                                    .filters(new ArrayList<>(variant.getFilters())).make())
                    .make();

            final List<Allele> alleles2 = new ArrayList<>(2);
            alleles2.add(Allele.create(baseAtEnd, true));
            alleles2.add(Allele.create((char) baseAtEnd + "]" + variant.getContig() + ":" + variant.getStart() + "]"));

            final VariantContext bnd2 = new VariantContextBuilder(variant)
                    .id(id + "_2")
                    .start(end)
                    .alleles(alleles2)
                    .log10PError(variant.getLog10PError())
                    .attribute(GATKSVVCFConstants.SVTYPE, "BND")
                    .attribute("SVTYPE2", "DUP")
                    .rmAttribute("END")
                    .rmAttribute("SVLEN")
                    .rmAttribute("CIEND")
                    .attribute("CIPOS", variant.getAttribute("CIEND"))
                    .genotypes(
                            new GenotypeBuilder(genotype)
                                    .alleles(Arrays.asList(alleles2.get(originalAlleles.indexOf(genotype.getAllele(0))),
                                            alleles2.get(originalAlleles.indexOf(genotype.getAllele(1)))))
                                    .phased(genotype.isPhased())
                                    .attribute("Qual", variant.getPhredScaledQual())
                                    .attribute("PS", variant.getAttribute("PS"))
                                    .attribute("Pairs", variant.getAttribute("PAIRS"))
                                    .attribute("Split", variant.getAttribute("SPLIT"))
                                    .filters(new ArrayList<>(variant.getFilters())).make())
                    .make();

            final List<Allele> alleles3 = new ArrayList<>(2);
            alleles3.add(Allele.create(baseAfterPos, true));
            alleles3.add(Allele.create("[" + variant.getContig() + ":" + (variant.getEnd() + 1) + "[" + (char) baseAfterPos));

            final VariantContext bnd3 = new VariantContextBuilder(variant)
                    .id(id + "_3")
                    .start(variant.getStart() + 1)
                    .alleles(alleles3)
                    .log10PError(variant.getLog10PError())
                    .attribute(GATKSVVCFConstants.SVTYPE, "BND")
                    .attribute("SVTYPE2", "INV")
                    .rmAttribute("END")
                    .rmAttribute("SVLEN")
                    .rmAttribute("CIEND")
                    .attribute("CIPOS", variant.getAttribute("CIPOS"))
                    .genotypes(
                            new GenotypeBuilder(genotype)
                                    .alleles(Arrays.asList(alleles3.get(originalAlleles.indexOf(genotype.getAllele(0))),
                                            alleles3.get(originalAlleles.indexOf(genotype.getAllele(1)))))
                                    .phased(genotype.isPhased())
                                    .attribute("Qual", variant.getPhredScaledQual())
                                    .attribute("PS", variant.getAttribute("PS"))
                                    .attribute("Pairs", variant.getAttribute("PAIRS"))
                                    .attribute("Split", variant.getAttribute("SPLIT"))
                                    .filters(new ArrayList<>(variant.getFilters())).make())
                    .make();

            final List<Allele> alleles4 = new ArrayList<>(2);
            alleles4.add(Allele.create(baseAfterEnd, true));
            alleles4.add(Allele.create("[" + variant.getContig() + ":" + (variant.getStart() + 1) + "[" + (char) baseAfterEnd));
            //alleles4.add(createBreakendAllele(baseAfterEnd, variant.getContig(), variant.getStart() + 1, false, true));

            final VariantContext bnd4 = new VariantContextBuilder(variant)
                    .id(id + "_4")
                    .start(end + 1)
                    .alleles(alleles4)
                    .log10PError(variant.getLog10PError())
                    .attribute(GATKSVVCFConstants.SVTYPE, "BND")
                    .attribute("SVTYPE2", "INV")
                    .rmAttribute("END")
                    .rmAttribute("SVLEN")
                    .rmAttribute("CIEND")
                    .attribute("CIPOS", variant.getAttribute("CIEND"))
                    .genotypes(
                            new GenotypeBuilder(genotype)
                                    .alleles(Arrays.asList(alleles4.get(originalAlleles.indexOf(genotype.getAllele(0))),
                                            alleles4.get(originalAlleles.indexOf(genotype.getAllele(1)))))
                                    .phased(genotype.isPhased())
                                    .attribute("Qual", variant.getPhredScaledQual())
                                    .attribute("PS", variant.getAttribute("PS"))
                                    .attribute("Pairs", variant.getAttribute("PAIRS"))
                                    .attribute("Split", variant.getAttribute("SPLIT"))
                                    .filters(new ArrayList<>(variant.getFilters())).make())
                    .make();

            variants.add(bnd1);
            variants.add(bnd2);
            variants.add(bnd3);
            variants.add(bnd4);
        } else if (variant.getAttribute(GATKSVVCFConstants.SVTYPE).equals("UNK")) {
            final String id = originalVariantId + "_" + sample + "_UNK";
            final VariantContextBuilder builder = new VariantContextBuilder(variant);
            final List<Allele> alleles = new ArrayList<>(variant.getAlleles().size());
            alleles.add(Allele.create(referenceContext.getBase(), true));
            alleles.addAll(variant.getAlternateAlleles());

            final GenotypeBuilder genotypeBuilder =
                    new GenotypeBuilder(sample)
                            .alleles(Arrays.asList(alleles.get(originalAlleles.indexOf(genotype.getAllele(0))),
                                    alleles.get(originalAlleles.indexOf(genotype.getAllele(1)))))
                            .attribute("Qual", variant.getPhredScaledQual())
                            .attribute("PS", variant.getAttribute("PS"))
                            .attribute("Pairs", variant.getAttribute("Pairs"))
                            .attribute("Split", variant.getAttribute("Split"))
                            .attribute("FT", variant.getFilters());


            final VariantContext vc = builder
                    .id(id)
                    .alleles(alleles)
                    .genotypes(genotypeBuilder.make())
                    .make();

            variants.add(vc);
        } else {
            System.err.println("Unknown variant type " + variant + ", skipping");
        }
    }

    @Override
    public Object onTraversalSuccess() {

        variants.stream().sorted((VariantContext v1, VariantContext v2) -> {
            final int x = IntervalUtils.compareLocatables(v1, v2, getReferenceDictionary());
            if (x == 0) {
                final String s1 = v1.getAttributeAsString(GATKSVVCFConstants.INSERTED_SEQUENCE, "");
                final String s2 = v2.getAttributeAsString(GATKSVVCFConstants.INSERTED_SEQUENCE, "");
                return s1.compareTo(s2);
            } else {
                return x;
            }
        }).forEach(vc -> vcfWriter.add(vc));

        vcfWriter.close();

        try {
            referenceReader.close();
        } catch (IOException e) {
            throw new GATKException("Could not close reference reader");
        }
        return null;
    }


//    static Allele createBreakendAllele(final byte posBase, final String mateBreakendContig, final int mateBreakendPos, final boolean joinAfterPos, final boolean mateBreakendReverseComplement) {
//        return createBreakendAllele(new byte[] {posBase }, mateBreakendContig, mateBreakendPos, joinAfterPos, mateBreakendReverseComplement);
//
//    }
//
//    static Allele createBreakendAllele(final byte[] posBases, final String mateBreakendContig, final int mateBreakendPos, final boolean joinAfterPos, final boolean mateBreakendReverseComplement) {
//        //alleles4.add(Allele.create("[" + variant.getContig() + ":" + (variant.getStart() + 1) + "[" + (char) baseAfterEnd));
//        boolean
//
//    }

}
