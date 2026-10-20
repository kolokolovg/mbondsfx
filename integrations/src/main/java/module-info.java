module ru.onlychill.mbondsfx.integrations {
    requires transitive ru.onlychill.mbondsfx.application;
    requires java.net.http;
    requires java.xml;

    exports ru.onlychill.mbondsfx.integrations.provider;
    exports ru.onlychill.mbondsfx.integrations.transport
            to ru.onlychill.mbondsfx.integrations.moex, ru.onlychill.mbondsfx.integrations.cbr;
    exports ru.onlychill.mbondsfx.integrations.xml
            to ru.onlychill.mbondsfx.integrations.moex, ru.onlychill.mbondsfx.integrations.cbr;
    exports ru.onlychill.mbondsfx.integrations.index
            to ru.onlychill.mbondsfx.integrations.moex, ru.onlychill.mbondsfx.integrations.cbr;
    exports ru.onlychill.mbondsfx.integrations.adapter
            to ru.onlychill.mbondsfx.integrations.moex, ru.onlychill.mbondsfx.integrations.cbr;
}
