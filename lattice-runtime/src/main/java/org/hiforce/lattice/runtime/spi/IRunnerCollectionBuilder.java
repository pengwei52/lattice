package org.hiforce.lattice.runtime.spi;


import org.hifforce.lattice.model.ability.IAbility;
import org.hifforce.lattice.model.ability.IBusinessExt;
import org.hiforce.lattice.runtime.ability.execute.RunnerCollection;

/**
 * @author Rocky Yu
 * @since 2022/9/19
 */
@SuppressWarnings("all")
public interface IRunnerCollectionBuilder<ExtensionPoints extends IBusinessExt> {

    boolean isSupport(IAbility ability, String extCode);

    <R> RunnerCollection<ExtensionPoints, R> buildCustomRunnerCollection(
            IAbility ability, String extCode);
}