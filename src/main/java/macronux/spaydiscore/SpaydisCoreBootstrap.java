package macronux.spaydiscore;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

public class SpaydisCoreBootstrap implements PluginBootstrap {

    private static final String DATAPACK_RESOURCE = "/spaydiscore_datapack";
    private static final String DATAPACK_ID = "provided";

    @Override
    public void bootstrap(final BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(
                LifecycleEvents.DATAPACK_DISCOVERY.newHandler(event -> {
                    URL resource = SpaydisCoreBootstrap.class.getResource(DATAPACK_RESOURCE);
                    if (resource == null) {
                        throw new IllegalStateException(
                                "Bundled SpaydisCore datapack resource is missing: " + DATAPACK_RESOURCE);
                    }

                    try {
                        URI datapackUri = resource.toURI();
                        event.registrar().discoverPack(datapackUri, DATAPACK_ID);
                    } catch (URISyntaxException | IOException e) {
                        throw new IllegalStateException(
                                "Failed to register the bundled SpaydisCore datapack", e);
                    }
                })
        );
    }
}
