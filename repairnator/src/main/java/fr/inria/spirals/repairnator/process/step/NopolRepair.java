package fr.inria.spirals.repairnator.process.step;

import fr.inria.lille.commons.synthesis.smt.solver.SolverFactory;
import fr.inria.lille.repair.ProjectReference;
import fr.inria.lille.repair.common.config.Config;
import fr.inria.lille.repair.common.patch.Patch;
import fr.inria.lille.repair.common.synth.StatementType;
import fr.inria.lille.repair.nopol.NoPol;
import fr.inria.spirals.repairnator.process.inspectors.ProjectInspector;
import fr.inria.spirals.repairnator.process.ProjectState;
import fr.inria.spirals.repairnator.process.testinformation.ComparatorFailureLocation;
import fr.inria.spirals.repairnator.process.testinformation.FailureLocation;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Created by urli on 05/01/2017.
 */
public class NopolRepair extends AbstractStep {
    private static final int TOTAL_MAX_TIME = 10; // We expect it to run 4 hours top.
    private static final int MIN_TIMEOUT = 2;

    private Map<String,List<Patch>> patches;


    public NopolRepair(ProjectInspector inspector) {
        super(inspector);
    }

    public Map<String,List<Patch>> getPatches() {
        return patches;
    }

    @Override
    protected void businessExecute() {
        this.getLogger().debug("Start to use nopol to repair...");
        List<URL> classPath = this.inspector.getRepairClassPath();
        File[] sources = this.inspector.getRepairSourceDir();

        GatherTestInformation infoStep = inspector.getTestInformations();
        List<FailureLocation> failureLocationList = new ArrayList<>(infoStep.getFailureLocations());
        Collections.sort(failureLocationList, new ComparatorFailureLocation());

        this.patches = new HashMap<String,List<Patch>>();
        boolean patchCreated = false;
        int passingTime = 0;

        for (FailureLocation failureLocation : failureLocationList) {
            String testClass = failureLocation.getClassName();
            int timeout = (TOTAL_MAX_TIME-passingTime)/2;
            if (timeout < MIN_TIMEOUT) {
                timeout = MIN_TIMEOUT;
            }

            this.getLogger().debug("Launching repair with Nopol for following test class: "+testClass+" (should timeout in "+timeout+" minutes)");

            ProjectReference projectReference = new ProjectReference(sources, classPath.toArray(new URL[classPath.size()]), new String[] {testClass});
            Config config = new Config();
            config.setComplianceLevel(8);
            config.setTimeoutTestExecution(60);
            config.setMaxTimeInMinutes(timeout);
            config.setLocalizer(Config.NopolLocalizer.GZOLTAR);
            config.setSolverPath(this.inspector.getNopolSolverPath());
            config.setSynthesis(Config.NopolSynthesis.DYNAMOTH);
            config.setType(StatementType.PRE_THEN_COND);

            SolverFactory.setSolver(config.getSolver(), config.getSolverPath());

            long beforeNopol = new Date().getTime();

            final NoPol nopol = new NoPol(projectReference, config);
            List<Patch> patch = null;

            final ExecutorService executor = Executors.newSingleThreadExecutor();
            final Future nopolExecution = executor.submit(
                    new Callable() {
                        @Override
                        public Object call() throws Exception {
                            return nopol.build(projectReference.testClasses());
                        }
                    });

            try {
                executor.shutdown();
                patch = (List<Patch>) nopolExecution.get(config.getMaxTimeInMinutes(), TimeUnit.MINUTES);
            } catch (TimeoutException exception) {
                this.addStepError("Timeout: execution time > " + config.getMaxTimeInMinutes() + " " + TimeUnit.MINUTES);
                nopolExecution.cancel(true);
            } catch (InterruptedException | ExecutionException e) {
                this.addStepError(e.getMessage());
                nopolExecution.cancel(true);
            }

            long afterNopol = new Date().getTime();

            passingTime = Math.round((afterNopol-beforeNopol)/60000);

            if (patch != null && !patch.isEmpty()) {
                this.patches.put(testClass, patch);
                patchCreated = true;
            }
        }

        if (!patchCreated) {
            this.addStepError("No patch has been generated by Nopol. Look at the trace to get more information.");
            return;
        }
        this.setState(ProjectState.PATCHED);

    }


}
