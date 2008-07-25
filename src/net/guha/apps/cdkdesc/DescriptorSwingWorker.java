package net.guha.apps.cdkdesc;


import net.guha.apps.cdkdesc.interfaces.ISwingWorker;
import net.guha.apps.cdkdesc.ui.ApplicationUI;
import org.openscience.cdk.CDKConstants;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.aromaticity.CDKHueckelAromaticityDetector;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.graph.ConnectivityChecker;
import org.openscience.cdk.interfaces.IAtom;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IMolecule;
import org.openscience.cdk.interfaces.IMoleculeSet;
import org.openscience.cdk.io.MDLWriter;
import org.openscience.cdk.io.iterator.DefaultIteratingChemObjectReader;
import org.openscience.cdk.io.iterator.IteratingMDLReader;
import org.openscience.cdk.io.iterator.IteratingSMILESReader;
import org.openscience.cdk.io.setting.BooleanIOSetting;
import org.openscience.cdk.io.setting.IOSetting;
import org.openscience.cdk.qsar.DescriptorValue;
import org.openscience.cdk.qsar.IDescriptor;
import org.openscience.cdk.qsar.IMolecularDescriptor;
import org.openscience.cdk.qsar.result.*;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;

import javax.swing.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * @author Rajarshi Guha
 */
public class DescriptorSwingWorker implements ISwingWorker {

    private ApplicationUI ui;
    private List<IDescriptor> descriptors;
    private List<ExceptionInfo> exceptionList;

    private String inputFormat = "mdl";
    private File tempFile;

    private int lengthOfTask = 1;
    private int current = 0;
    private int molCount = 0;
    private boolean done = false;
    private boolean canceled = false;


    public DescriptorSwingWorker(List<IDescriptor> descriptors,
                                 ApplicationUI ui, JProgressBar progressBar, File tempFile) {
        this.descriptors = descriptors;
        this.ui = ui;
        this.tempFile = tempFile;

        exceptionList = new ArrayList<ExceptionInfo>();

        // see what type of file we have
        inputFormat = "invalid";
        if (CDKDescUtils.isSMILESFormat(ui.getSdfFileTextField().getText())) {
            inputFormat = "smi";
        } else if (CDKDescUtils.isMDLFormat(ui.getSdfFileTextField().getText())) {
            inputFormat = "mdl";
        }

        if (inputFormat.equals("invalid")) {
            done = true;
            canceled = true;
            progressBar.setIndeterminate(false);
            JOptionPane.showMessageDialog(null,
                    "Input file format was not recognized. It should be SDF or SMI" +
                            "\nYou should avoid supplying Markush structures since will be" +
                            "\nignored anyway",
                    "CDKDescUI Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    public void go() {
        final SwingWorker worker = new SwingWorker() {
            public Object construct() {
                current = 0;
                done = false;
                canceled = false;
                try {
                    return new ActualTask();
                } catch (CDKException e) {
                    System.out.println("Problem! Contact rguha@indiana.edu\n\n" + e.toString());
                    System.exit(0);
                }
                return null;
            }
        };
        worker.start();
    }

    public List<ExceptionInfo> getExceptionList() {
        return exceptionList;
    }

    public String getInputFormat() {
        return inputFormat;
    }

    public int getLengthOfTask() {
        return lengthOfTask;
    }

    public int getCurrent() {
        return molCount;
    }

    public void stop() {
        canceled = true;
    }

    public boolean isDone() {
        return done;
    }

    public boolean isCancelled() {
        return canceled;
    }


    private IAtomContainer checkAndCleanMolecule(IAtomContainer molecule) throws CDKException {
        Iterator<IAtom> atoms = molecule.atoms();
        boolean isMarkush = false;
        while (atoms.hasNext()) {
            IAtom atom = atoms.next();
            if (atom.getSymbol().equals("R")) {
                isMarkush = true;
                break;
            }
        }

        if (isMarkush) {
            throw new CDKException("Skipping Markush structure");
        }

        // Check for salts and such
        if (!ConnectivityChecker.isConnected(molecule)) {
            // lets see if we have just two parts if so, we assume its a salt and just work
            // on the larger part. Ideally we should have a check to ensure that the smaller
            //  part is a metal/halogen etc.
            IMoleculeSet fragments = ConnectivityChecker.partitionIntoMolecules(molecule);
            if (fragments.getMoleculeCount() > 2) {
                throw new CDKException("More than 2 components. Skipped");
            } else {
                IMolecule frag1 = fragments.getMolecule(0);
                IMolecule frag2 = fragments.getMolecule(1);
                if (frag1.getAtomCount() > frag2.getAtomCount()) molecule = frag1;
                else molecule = frag2;
            }
        }

        // Do the configuration
        try {
            AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(molecule);
        } catch (CDKException e) {
            throw new CDKException("Error in atom typing" + e.toString());
        }

        // do a aromaticity check
        try {
            CDKHueckelAromaticityDetector.detectAromaticity(molecule);
        } catch (CDKException e) {
            throw new CDKException("Error in aromaticity detection");
        }

        return molecule;
    }

    class ActualTask {

        private boolean evalToTextFile(String sdfFileName, String outputFormat) throws CDKException {

            String lineSep = System.getProperty("line.separator");
            String itemSep = " ";

            if (outputFormat.equals(CDKDescConstants.OUTPUT_TAB)) {
                itemSep = "\t";
            } else if (outputFormat.equals(CDKDescConstants.OUTPUT_CSV)) {
                itemSep = ",";
            } else if (outputFormat.equals(CDKDescConstants.OUTPUT_SPC)) {
                itemSep = " ";
            }

            DefaultIteratingChemObjectReader iterReader = null;
            try {
                BufferedWriter tmpWriter = new BufferedWriter(new FileWriter(tempFile));

                FileInputStream inputStream = new FileInputStream(sdfFileName);
                if (inputFormat.equals("smi")) iterReader = new IteratingSMILESReader(inputStream);
                else if (inputFormat.equals("mdl")) {
                    BooleanIOSetting force3d = new BooleanIOSetting("ForceReadAs3DCoordinates", IOSetting.LOW,
                            "Should coordinates always be read as 3D?",
                            "true");
                    iterReader = new IteratingMDLReader(inputStream, DefaultChemObjectBuilder.getInstance());
//                    ((IteratingMDLReader)iterReader).
                }


                molCount = 0;

                // lets get the header line first
                for (IDescriptor descriptor : descriptors) {
                    String[] names = descriptor.getDescriptorNames();
                    for (String name : names) tmpWriter.write(name + itemSep);
                }
                tmpWriter.write(lineSep);

                assert iterReader != null;
                while (iterReader.hasNext()) {  // loop over molecules
                    if (canceled) return false;
                    IMolecule molecule = (IMolecule) iterReader.next();

                    try {
                        molecule = (IMolecule) checkAndCleanMolecule(molecule);
                    } catch (CDKException e) {
                        exceptionList.add(new ExceptionInfo(molCount + 1, molecule, e, ""));
                        molCount++;
                        continue;
                    }

                    // OK, we can now eval the descriptors
                    StringWriter stringWriter = new StringWriter();
                    for (Object object : descriptors) {
                        if (canceled) return false;
                        IMolecularDescriptor descriptor = (IMolecularDescriptor) object;
                        String[] comps = descriptor.getSpecification().getSpecificationReference().split("#");


                        DescriptorValue value = descriptor.calculate(molecule);
                        if (value.getException() != null) {
                            exceptionList.add(new ExceptionInfo(molCount + 1, molecule, value.getException(), comps[1]));
                            for (int i = 0; i < value.getNames().length; i++) stringWriter.write("NA" + itemSep);
                            continue;
                        }

                        IDescriptorResult result = value.getValue();
                        if (result instanceof DoubleResult) {
                            stringWriter.write(((DoubleResult) result).doubleValue() + itemSep);
                        } else if (result instanceof IntegerResult) {
                            stringWriter.write(((IntegerResult) result).intValue() + itemSep);
                        } else if (result instanceof DoubleArrayResult) {
                            for (int i = 0; i < ((DoubleArrayResult) result).length(); i++) {
                                stringWriter.write(((DoubleArrayResult) result).get(i) + itemSep);
                            }
                        } else if (result instanceof IntegerArrayResult) {
                            for (int i = 0; i < ((IntegerArrayResult) result).length(); i++) {
                                stringWriter.write(((IntegerArrayResult) result).get(i) + itemSep);
                            }
                        }
                        current++;
                    }

                    String dataLine = stringWriter.toString() + lineSep;
                    String pattern = itemSep + lineSep;
                    dataLine = dataLine.replace(pattern, lineSep);
                    dataLine = dataLine.replace("NaN", "NA");

                    String title = (String) molecule.getProperty(CDKConstants.TITLE);
                    if (title == null) title = "Mol" + String.valueOf(molCount + 1);
                    tmpWriter.write(title + itemSep + dataLine);
                    tmpWriter.flush();
                    molCount++;
                }
                iterReader.close();
                tmpWriter.close();

                done = true;
            } catch (IOException exception) {
                exception.printStackTrace();
            }
            return true;
        }

        private boolean evalToSDF(String sdfFileName) {
            DefaultIteratingChemObjectReader iterReader = null;
            try {
                MDLWriter tmpWriter = new MDLWriter(new FileWriter(tempFile));

                FileInputStream inputStream = new FileInputStream(sdfFileName);
                if (inputFormat.equals("smi")) iterReader = new IteratingSMILESReader(inputStream);
                else if (inputFormat.equals("mdl"))
                    iterReader = new IteratingMDLReader(inputStream, DefaultChemObjectBuilder.getInstance());

                int counter = 1;

                while (iterReader.hasNext()) {
                    if (canceled) return false;
                    IMolecule molecule = (IMolecule) iterReader.next();

                    try {
                        molecule = (IMolecule) checkAndCleanMolecule(molecule);
                    } catch (CDKException e) {
                        exceptionList.add(new ExceptionInfo(molCount + 1, molecule, e, ""));
                        molCount++;
                        continue;
                    }

                    HashMap<String, Object> map = new HashMap<String, Object>();
                    for (Object object : descriptors) {
                        if (canceled) return false;
                        IMolecularDescriptor descriptor = (IMolecularDescriptor) object;
                        String[] comps = descriptor.getSpecification().getSpecificationReference().split("#");

                        DescriptorValue value = descriptor.calculate(molecule);
                        if (value.getException() != null) {
                            exceptionList.add(new ExceptionInfo(counter, molecule, value.getException(), comps[1]));
                            String[] names = value.getNames();
                            for (String name : names) map.put(name, "NA");
                            continue;
                        }
                        String[] names = value.getNames();

                        IDescriptorResult result = value.getValue();
                        if (result instanceof DoubleResult) {
                            map.put(value.getNames()[0], ((DoubleResult) result).doubleValue());
                        } else if (result instanceof IntegerResult) {
                            map.put(value.getNames()[0], ((IntegerResult) result).intValue());
                        } else if (result instanceof DoubleArrayResult) {
                            for (int i = 0; i < ((DoubleArrayResult) result).length(); i++) {
                                map.put(names[i], ((DoubleArrayResult) result).get(i));
                            }
                        } else if (result instanceof IntegerArrayResult) {
                            for (int i = 0; i < ((IntegerArrayResult) result).length(); i++)
                                map.put(names[i], ((IntegerArrayResult) result).get(i));
                        }
                        current++;

                    }
                    tmpWriter.setSdFields(map);
                    tmpWriter.write(molecule);
                    counter++;
                }
                iterReader.close();
                tmpWriter.close();
                done = true;
            } catch (IOException exception) {
                exception.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
            return true;
        }


        ActualTask() throws CDKException {
            String outputMethod = AppOptions.getInstance().getOutputMethod();
            String sdfFileName = ui.getSdfFileTextField().getText();

            if (outputMethod.equals(CDKDescConstants.OUTPUT_TAB) ||
                    outputMethod.equals(CDKDescConstants.OUTPUT_CSV) ||
                    outputMethod.equals(CDKDescConstants.OUTPUT_SPC)) {
                boolean status = evalToTextFile(sdfFileName, outputMethod);
                if (!status) return;
            } else if (outputMethod.equals(CDKDescConstants.OUTPUT_SDF)) {
                boolean status = evalToSDF(sdfFileName);
                if (!status) return;
            }

        }
    }
}



