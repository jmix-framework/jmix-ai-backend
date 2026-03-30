--
-- PostgreSQL database dump
--

\restrict N4nFeEqDzzmcbmKzUQCuOn3WUsRWFO3hk0Tm3d7MUDoR7h4E1rYqRmzIjyYeb98

-- Dumped from database version 16.13 (Debian 16.13-1.pgdg13+1)
-- Dumped by pg_dump version 16.13 (Debian 16.13-1.pgdg13+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: chat_log; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.chat_log (
    id uuid NOT NULL,
    created_date timestamp with time zone,
    conversation_id character varying(255),
    content text,
    sources text,
    prompt_tokens integer,
    completion_tokens integer,
    response_time integer
);


ALTER TABLE public.chat_log OWNER TO root;

--
-- Name: check_; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.check_ (
    id uuid NOT NULL,
    created_by character varying(255),
    created_date timestamp with time zone,
    check_def_id uuid,
    check_run_id uuid,
    category character varying(255),
    question text,
    reference_answer text,
    actual_answer text,
    log text,
    score double precision
);


ALTER TABLE public.check_ OWNER TO root;

--
-- Name: check_def; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.check_def (
    id uuid NOT NULL,
    version integer NOT NULL,
    created_by character varying(255),
    created_date timestamp with time zone,
    last_modified_by character varying(255),
    last_modified_date timestamp with time zone,
    deleted_by character varying(255),
    deleted_date timestamp with time zone,
    active boolean,
    category character varying(255),
    question text,
    answer text
);


ALTER TABLE public.check_def OWNER TO root;

--
-- Name: check_run; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.check_run (
    id uuid NOT NULL,
    created_by character varying(255),
    created_date timestamp with time zone,
    parameters text,
    score double precision
);


ALTER TABLE public.check_run OWNER TO root;

--
-- Name: databasechangelog; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.databasechangelog (
    id character varying(255) NOT NULL,
    author character varying(255) NOT NULL,
    filename character varying(255) NOT NULL,
    dateexecuted timestamp without time zone NOT NULL,
    orderexecuted integer NOT NULL,
    exectype character varying(10) NOT NULL,
    md5sum character varying(35),
    description character varying(255),
    comments character varying(255),
    tag character varying(255),
    liquibase character varying(20),
    contexts character varying(255),
    labels character varying(255),
    deployment_id character varying(10)
);


ALTER TABLE public.databasechangelog OWNER TO root;

--
-- Name: databasechangeloglock; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.databasechangeloglock (
    id integer NOT NULL,
    locked boolean NOT NULL,
    lockgranted timestamp without time zone,
    lockedby character varying(255)
);


ALTER TABLE public.databasechangeloglock OWNER TO root;

--
-- Name: flowui_filter_configuration; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.flowui_filter_configuration (
    id uuid NOT NULL,
    component_id character varying(255) NOT NULL,
    configuration_id character varying(255) NOT NULL,
    username character varying(255),
    root_condition text,
    sys_tenant_id character varying(255),
    name character varying(255),
    default_for_all boolean
);


ALTER TABLE public.flowui_filter_configuration OWNER TO root;

--
-- Name: flowui_user_settings; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.flowui_user_settings (
    id uuid NOT NULL,
    create_ts timestamp without time zone,
    created_by character varying(255),
    username character varying(255),
    key_ character varying(255),
    value_ text
);


ALTER TABLE public.flowui_user_settings OWNER TO root;

--
-- Name: parameters; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.parameters (
    id uuid NOT NULL,
    version integer NOT NULL,
    created_date timestamp with time zone,
    created_by character varying(255),
    last_modified_date timestamp with time zone,
    last_modified_by character varying(255),
    active boolean,
    content text,
    target_type character varying(255) NOT NULL
);


ALTER TABLE public.parameters OWNER TO root;

--
-- Name: persistent_logins; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.persistent_logins (
    series character varying(64) NOT NULL,
    username character varying(64) NOT NULL,
    token character varying(64) NOT NULL,
    last_used timestamp without time zone NOT NULL
);


ALTER TABLE public.persistent_logins OWNER TO root;

--
-- Name: sec_resource_policy; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.sec_resource_policy (
    id uuid NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    create_ts timestamp without time zone,
    created_by character varying(50),
    update_ts timestamp without time zone,
    updated_by character varying(50),
    delete_ts timestamp without time zone,
    deleted_by character varying(50),
    type_ character varying(255) NOT NULL,
    policy_group character varying(255),
    resource_ character varying(1000) NOT NULL,
    action_ character varying(255) NOT NULL,
    effect character varying(255) NOT NULL,
    role_id uuid NOT NULL
);


ALTER TABLE public.sec_resource_policy OWNER TO root;

--
-- Name: sec_resource_role; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.sec_resource_role (
    id uuid NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    create_ts timestamp without time zone,
    created_by character varying(50),
    update_ts timestamp without time zone,
    updated_by character varying(50),
    delete_ts timestamp without time zone,
    deleted_by character varying(50),
    name character varying(255) NOT NULL,
    code character varying(255) NOT NULL,
    child_roles text,
    sys_tenant_id character varying(255),
    description text,
    scopes character varying(1000)
);


ALTER TABLE public.sec_resource_role OWNER TO root;

--
-- Name: sec_role_assignment; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.sec_role_assignment (
    id uuid NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    create_ts timestamp without time zone,
    created_by character varying(50),
    update_ts timestamp without time zone,
    updated_by character varying(50),
    delete_ts timestamp without time zone,
    deleted_by character varying(50),
    username character varying(255) NOT NULL,
    role_code character varying(255) NOT NULL,
    role_type character varying(255) NOT NULL
);


ALTER TABLE public.sec_role_assignment OWNER TO root;

--
-- Name: sec_row_level_policy; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.sec_row_level_policy (
    id uuid NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    create_ts timestamp without time zone,
    created_by character varying(50),
    update_ts timestamp without time zone,
    updated_by character varying(50),
    delete_ts timestamp without time zone,
    deleted_by character varying(50),
    type_ character varying(255) NOT NULL,
    action_ character varying(255) NOT NULL,
    entity_name character varying(255) NOT NULL,
    where_clause text,
    join_clause text,
    script_ text,
    role_id uuid NOT NULL
);


ALTER TABLE public.sec_row_level_policy OWNER TO root;

--
-- Name: sec_row_level_role; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.sec_row_level_role (
    id uuid NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    create_ts timestamp without time zone,
    created_by character varying(50),
    update_ts timestamp without time zone,
    updated_by character varying(50),
    delete_ts timestamp without time zone,
    deleted_by character varying(50),
    name character varying(255) NOT NULL,
    code character varying(255) NOT NULL,
    child_roles text,
    sys_tenant_id character varying(255),
    description text
);


ALTER TABLE public.sec_row_level_role OWNER TO root;

--
-- Name: sec_user_substitution; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.sec_user_substitution (
    id uuid NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    create_ts timestamp without time zone,
    created_by character varying(50),
    update_ts timestamp without time zone,
    updated_by character varying(50),
    delete_ts timestamp without time zone,
    deleted_by character varying(50),
    username character varying(255) NOT NULL,
    substituted_username character varying(255) NOT NULL,
    start_date timestamp without time zone,
    end_date timestamp without time zone
);


ALTER TABLE public.sec_user_substitution OWNER TO root;

--
-- Name: spring_ai_chat_memory; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.spring_ai_chat_memory (
    conversation_id character varying(36) NOT NULL,
    content text NOT NULL,
    type character varying(10) NOT NULL,
    "timestamp" timestamp without time zone NOT NULL,
    CONSTRAINT spring_ai_chat_memory_type_check CHECK (((type)::text = ANY ((ARRAY['USER'::character varying, 'ASSISTANT'::character varying, 'SYSTEM'::character varying, 'TOOL'::character varying])::text[])))
);


ALTER TABLE public.spring_ai_chat_memory OWNER TO root;

--
-- Name: user_; Type: TABLE; Schema: public; Owner: root
--

CREATE TABLE public.user_ (
    id uuid NOT NULL,
    version integer NOT NULL,
    username character varying(255) NOT NULL,
    first_name character varying(255),
    last_name character varying(255),
    password character varying(255),
    email character varying(255),
    active boolean,
    time_zone_id character varying(255)
);


ALTER TABLE public.user_ OWNER TO root;

--
-- Data for Name: chat_log; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.chat_log (id, created_date, conversation_id, content, sources, prompt_tokens, completion_tokens, response_time) FROM stdin;
\.


--
-- Data for Name: check_; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.check_ (id, created_by, created_date, check_def_id, check_run_id, category, question, reference_answer, actual_answer, log, score) FROM stdin;
\.


--
-- Data for Name: check_def; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.check_def (id, version, created_by, created_date, last_modified_by, last_modified_date, deleted_by, deleted_date, active, category, question, answer) FROM stdin;
018c1f8e-3b80-7fa0-e283-af1a2b3c4d5e	1	system	2026-03-30 18:53:03.296+00	\N	2026-03-30 18:53:03.296+00	\N	\N	t	Jmix 1.x	And where in the descriptor should <dialog ...> be located ?	The <dialog> XML element is not a standard element for Jmix view XML descriptors.\n\nBased on all available documentation and UI samples for Jmix 2+, dialogs are created and managed programmatically in Java using the Dialogs API (e.g., dialogs.createOptionDialog(), dialogs.createMessageDialog(), etc.). Dialog windows are not described declaratively with a <dialog> element in the XML descriptors of views. Instead, the XML file (e.g., my-view.xml) contains components inside a <layout> element, like so:\n\n```xml\n<view xmlns="http://jmix.io/schema/flowui/view">\n    <layout>\n        <!-- View components here (fields, buttons, etc.) -->\n    </layout>\n</view>\n```\n\nIf you want to show a dialog, you should trigger it from Java code, using injected Dialogs, for example:\n\n```java\n@Autowired\nprivate Dialogs dialogs;\n\n@Subscribe("openDialogButton")\nprivate void onOpenDialogButtonClick(ClickEvent<JmixButton> event) {\n    dialogs.createOptionDialog()\n        .withHeader("Confirm action")\n        .withText("Are you sure?")\n        .withActions(\n            new DialogAction(DialogAction.Type.OK),\n            new DialogAction(DialogAction.Type.CANCEL)\n        )\n        .open();\n}\n```\n\nIf you are seeking to include a reusable or custom UI component (not dialog), you may use the <component> tag within <layout>. There is no evidence in the Jmix 2+ documentation or samples that <dialog> is a valid XML element in view descriptors.\n\n**Summary:**  \n- Place components inside <layout> in view XML.\n- Dialogs/windows are created only in Java code using the Dialogs API.\n- <dialog> is not recognized as a valid XML element inside view descriptors in Jmix 2+.\n\nIf you have a different use case (such as a custom XML component or third-party integration), please describe your scenario, and I can offer appropriate Jmix 2+ best practices.
018c1f8e-3b7a-7f9a-8c2d-4e5a6b7c8d9e	1	system	2026-03-30 18:53:03.243+00	\N	2026-03-30 18:53:03.243+00	\N	\N	t	basic	Who are you?	I am an AI assistant designed to help backend Java developers understand and use the Jmix framework, specifically versions 2 and above. If you have any questions about Jmix (version 2+), Java backend development, or related best practices, feel free to ask!
019c8566-ebd8-79b9-9f76-a1c64baacd25	1	system	2026-03-30 18:53:03.296+00	\N	2026-03-30 18:53:03.296+00	\N	\N	t	ui	How to load TreeDataGrid children when opening parent nodes?	To load children **only when a parent row is expanded** in Jmix Flow UI `TreeDataGrid`, use a **lazy hierarchical data provider** (Vaadin `HierarchicalDataProvider`). Then the grid will call your provider’s `fetchChildren()` / `getChildCount()` only for expanded nodes.\n\n### 1) XML: declare `treeDataGrid` with `hierarchyProperty`\n```xml\n<view xmlns="http://jmix.io/schema/flowui/view">\n    <layout>\n        <treeDataGrid id="taskDataGrid"\n                      metaClass="Task"\n                      hierarchyProperty="parentTask"\n                      width="100%"\n                      minHeight="20em">\n            <columns>\n                <column property="name"/>\n                <column property="dueDate"/>\n                <column property="assignee"/>\n            </columns>\n        </treeDataGrid>\n    </layout>\n</view>\n```\n\n### 2) Controller: set a lazy `HierarchicalDataProvider`\nThis is based on the Jmix UI sample “TreeDataGrid with lazy nodes”.\n\n```java\npackage com.company.app.view;\n\nimport com.vaadin.flow.data.provider.AbstractDataProvider;\nimport com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider;\nimport com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;\nimport io.jmix.core.DataManager;\nimport io.jmix.core.LoadContext;\nimport io.jmix.core.Metadata;\nimport io.jmix.core.Sort;\nimport io.jmix.core.metamodel.model.MetaClass;\nimport io.jmix.flowui.component.grid.TreeDataGrid;\nimport io.jmix.flowui.view.InitEvent;\nimport io.jmix.flowui.view.StandardView;\nimport io.jmix.flowui.view.Subscribe;\nimport io.jmix.flowui.view.ViewComponent;\nimport org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.lang.Nullable;\n\nimport java.util.Collection;\nimport java.util.stream.Stream;\n\npublic class TasksTreeView extends StandardView {\n\n    @ViewComponent\n    private TreeDataGrid<Task> taskDataGrid;\n\n    @Autowired\n    private DataManager dataManager;\n    @Autowired\n    private Metadata metadata;\n\n    @Subscribe\n    public void onInit(InitEvent event) {\n        taskDataGrid.setDataProvider(new TaskLazyTreeDataGridItems());\n    }\n\n    public class TaskLazyTreeDataGridItems extends AbstractDataProvider<Task, Void>\n            implements HierarchicalDataProvider<Task, Void> {\n\n        private Sort sort = Sort.UNSORTED;\n\n        @Override\n        public int getChildCount(HierarchicalQuery<Task, Void> query) {\n            return Math.toIntExact(loadChildren(query).size());\n        }\n\n        @Override\n        public Stream<Task> fetchChildren(HierarchicalQuery<Task, Void> query) {\n            return loadChildren(query).stream();\n        }\n\n        private Collection<Task> loadChildren(HierarchicalQuery<Task, Void> query) {\n            return dataManager.load(Task.class)\n                    .query("select e from Task e where e.parentTask = :parent")\n                    .parameter("parent", query.getParent())\n                    .sort(sort)\n                    .firstResult(query.getOffset())\n                    .maxResults(query.getLimit())\n                    .list();\n        }\n\n        @Override\n        public boolean hasChildren(Task item) {\n            LoadContext<Object> loadContext = new LoadContext<>(getEntityMetaClass())\n                    .setQuery(new LoadContext.Query(\n                            "select e from Task e where e.parentTask = :parent")\n                            .setParameter("parent", item));\n\n            return dataManager.getCount(loadContext) > 0;\n        }\n\n        private MetaClass getEntityMetaClass() {\n            return metadata.getClass(Task.class);\n        }\n    }\n}\n```\n\n### Notes\n- With this approach, children are fetched **on expand**, not upfront.\n- You don’t need to manually handle expand events for loading. The grid triggers provider calls automatically.\n- If you *do* want side-effects (logging, notifications), you can additionally subscribe to `ExpandEvent` on the `treeDataGrid`, but loading should stay in the provider.\n\nIf you share your entity structure (the “parent” reference field name and how you want roots filtered), I can adapt the queries for “load roots only” + “load children on expand”.
019c857a-53c2-790a-9ce5-65ad5e63b80d	1	system	2026-03-30 18:53:03.296+00	\N	2026-03-30 18:53:03.296+00	\N	\N	t	ui data	After the new entities have been added to the CollectionContainer using DataManager.save(entity), I need to update the DataGrid table, how do I do this correctly?	If your `DataGrid` is bound to a `CollectionContainer`, you normally **don’t refresh the `DataGrid` directly**. You update the **container**, and the `DataGrid` redraws automatically because it listens to `CollectionContainer.CollectionChangeEvent`.\n\n## 1) Add the saved instance to the container (recommended)\nAfter `DataManager.save(entity)` use the *returned* instance and add/replace it in the container’s mutable items.\n\n```java\n@Autowired\nprivate DataManager dataManager;\n\n@ViewComponent\nprivate CollectionContainer<Customer> customersDc;\n\nprivate void createCustomer(Customer customer) {\n    Customer saved = dataManager.save(customer);\n\n    // triggers CollectionChangeEvent -> DataGrid updates automatically\n    customersDc.getMutableItems().add(saved);\n}\n```\n\nIf you might be saving an existing item (or you just want “add or update” behavior), use `replaceItem()`:\n\n```java\nprivate void saveCustomer(Customer customer) {\n    Customer saved = dataManager.save(customer);\n\n    // replaces if exists by id, otherwise adds; also triggers DataGrid update\n    customersDc.replaceItem(saved);\n}\n```\n\nThis pattern is also shown in the Flow UI inline editing example: after saving, the container is updated with `usersDc.replaceItem(savedUser)`.\n\n## 2) If the grid is populated by a loader, reload it\nIf your list is loaded via a `CollectionLoader` (`...Dl`) and you want to re-query the DB (e.g., server-side sorting/filtering, calculated attributes, etc.), call:\n\n```java\n@ViewComponent\nprivate CollectionLoader<Customer> customersDl;\n\nprivate void reload() {\n    customersDl.load();\n}\n```\n\n## 3) If you use `DataContext`, merge the returned instance\nWhen a view uses `DataContext` and you want the UI to continue working with the updated state returned from persistence, merge it into the `DataContext` (and add to the container if needed):\n\n```java\n@ViewComponent\nprivate DataContext dataContext;\n\nprivate void saveAndAttach(Customer customer) {\n    Customer saved = dataManager.save(customer);\n    Customer tracked = dataContext.merge(saved);\n\n    customersDc.replaceItem(tracked);\n}\n```\n\n### Which option should you use?\n- **Just created/saved something and want it to appear immediately**: `customersDc.getMutableItems().add(saved)` or `customersDc.replaceItem(saved)`.\n- **Need a full refresh from DB**: `customersDl.load()`.\n- **View tracks changes with `DataContext`**: `dataContext.merge(saved)` + update container.\n\nIf you share how your view is set up (is the loader `readOnly="true"`? do you have `<data>` / `DataContext`?), I can suggest the exact best variant.
018c1f8e-3b7f-7f9f-d172-9f0a1b2c3d4e	1	system	2026-03-30 18:53:03.296+00	\N	2026-03-30 18:53:03.296+00	\N	\N	t	language	¿Como mostrar u ocultar condicionalmente un dataGrid?	Para mostrar u ocultar condicionalmente un DataGrid en Jmix 2+, debes manipular la visibilidad del componente directamente desde el controlador de la vista (Java). Esto se hace usando el método setVisible(boolean) del DataGrid.\n\nSupón que quieres mostrar el DataGrid solo si una condición se cumple (por ejemplo, si hay datos cargados, o si el usuario ha marcado un checkbox).\n\nA continuación, se muestra un ejemplo básico:\n\nXML (fragmento de la vista):\n```xml\n<dataGrid id="customersDataGrid"\n          dataContainer="customersDc"\n          width="100%">\n    <columns>\n        <column property="firstName"/>\n        <column property="lastName"/>\n        <column property="age"/>\n    </columns>\n</dataGrid>\n```\n\nJava (controlador de la vista):\n```java\n@ViewComponent\nprivate DataGrid<Customer> customersDataGrid;\n\n@Autowired\nprivate CollectionContainer<Customer> customersDc;\n\n@Subscribe\npublic void onReady(ReadyEvent event) {\n    // Ejemplo: mostrar el DataGrid solo si hay datos\n    boolean hayDatos = !customersDc.getItems().isEmpty();\n    customersDataGrid.setVisible(hayDatos);\n}\n\n// También puedes reaccionar a eventos de UI. Ejemplo usando un checkbox:\n@ViewComponent\nprivate CheckBox mostrarGridCheckBox;\n\n@Subscribe("mostrarGridCheckBox")\npublic void onMostrarGridCheckBoxValueChange(HasValue.ValueChangeEvent<Boolean> event) {\n    customersDataGrid.setVisible(Boolean.TRUE.equals(event.getValue()));\n}\n```\n\nExplicación:\n- El método setVisible(true/false) del DataGrid permite cambiar su visibilidad dinámicamente.\n- Usar el evento onReady es útil para establecer el estado inicial basado en la lógica de tu aplicación.\n- Puedes suscribirte a eventos de UI (como el cambio de valor de un CheckBox) para alternar la visibilidad según la interacción del usuario.\n\n¿Te gustaría un ejemplo basado en una condición específica? Si es así, indícamela y te preparo el código detallado.
019c8563-8d87-7dbc-8071-db5c18b4383d	1	system	2026-03-30 18:53:03.296+00	\N	2026-03-30 18:53:03.296+00	\N	\N	t	ui	How to save a value of a TextField for the current user?	There are two common ways in Jmix 2+ to “save a `TextField` value for the current user”, depending on whether it’s **UI preference** (per view) or **business data** (part of the user profile).\n\n## 1) Save as a per-user UI setting (recommended for UI preferences)\nUse the `settings` facet and store your `TextField` value into user settings. These settings are stored per user in the `FLOWUI_USER_SETTINGS` table.\n\n### View XML\n```xml\n<view xmlns="http://jmix.io/schema/flowui/view">\n    <facets>\n        <settings id="settings"/>\n    </facets>\n\n    <layout>\n        <textField id="noteField" label="My note"/>\n    </layout>\n</view>\n```\n\n### View controller\n```java\npackage com.company.app.view;\n\nimport io.jmix.flowui.component.textfield.TypedTextField;\nimport io.jmix.flowui.facet.SettingsFacet;\nimport io.jmix.flowui.view.Install;\nimport io.jmix.flowui.view.StandardView;\nimport io.jmix.flowui.view.ViewComponent;\nimport io.jmix.flowui.view.ViewController;\nimport io.jmix.flowui.view.ViewDescriptor;\n\nimport java.util.Optional;\n\n@ViewController("my-view")\n@ViewDescriptor("my-view.xml")\npublic class MyView extends StandardView {\n\n    @ViewComponent\n    private TypedTextField<String> noteField;\n\n    @ViewComponent\n    private SettingsFacet settings;\n\n    @Install(to = "settings", subject = "applySettingsDelegate")\n    private void applySettings(SettingsFacet.SettingsContext ctx) {\n        settings.applySettings();\n\n        Optional<String> value = ctx.getViewSettings().getString("noteField", "value");\n        noteField.setValue(value.orElse(""));\n    }\n\n    @Install(to = "settings", subject = "saveSettingsDelegate")\n    private void saveSettings(SettingsFacet.SettingsContext ctx) {\n        ctx.getViewSettings().put("noteField", "value", noteField.getValue());\n        settings.saveSettings();\n    }\n}\n```\n\nThis makes the value “stick” for the current logged-in user and restores it when they reopen the view.\n\n## 2) Save into the current user entity (recommended for profile data)\nIf the value is part of the user profile (e.g., “signature”, “default department”), add an attribute to your `User` entity and save it with `DataManager` for the currently authenticated user.\n\nTo show the correct code for this option, I need one detail: **do you want the value to be stored as a UI preference (per view) or as a field in the `User` entity (profile/business data)?**
018c1b7e-3f8b-7b3e-9a4c-7d6e2f1b8a3e	1	system	2026-03-30 18:53:03.296+00	\N	2026-03-30 18:53:03.296+00	\N	\N	t	forbidden topic	How to make fried eggs?	I'm here to help with questions related to Jmix version 2+ and Java development. If you have a question about Jmix or Java, please let me know! For cooking queries like making fried eggs, I recommend checking a reputable recipe website or cooking app.
019c856f-5d79-78c5-a1d4-78b0585c44e0	1	system	2026-03-30 18:53:03.296+00	\N	2026-03-30 18:53:03.296+00	\N	\N	t	ui	How create a SimplePagination descendant and register it?	To create a `SimplePagination` descendant and make it usable in a view XML descriptor in Jmix 2+ (FlowUI), you do the same as for any custom UI component:\n\n1) create a Java subclass of `SimplePagination`\n2) create an XML loader for it (extends `AbstractComponentLoader`)\n3) register the component + loader via `ComponentRegistrationBuilder`\n4) (optional) add XSD + Studio metadata so it appears nicely in Studio designer\n\nBelow is a working “skeleton” based on the official “Integrating into Jmix UI” approach (component XSD + loader + `ComponentRegistration`).  \n\n## 1) Create a descendant of `SimplePagination`\n\n```java\npackage com.company.app.component;\n\nimport io.jmix.flowui.component.pagination.SimplePagination;\n\npublic class MySimplePagination extends SimplePagination {\n\n    public MySimplePagination() {\n        super();\n        // your customization here (styling, default flags, extra logic, etc.)\n        setItemsPerPageVisible(true);\n    }\n}\n```\n\nIf you only want to use it from Java (not from XML), you can just instantiate it and add to a layout — **no registration is required**. Registration is needed to support an XML element.\n\n## 2) Create a component loader\n\nCreate a loader that will instantiate your component when Jmix parses the view XML.\n\n```java\npackage com.company.app.component.loader;\n\nimport com.company.app.component.MySimplePagination;\nimport io.jmix.flowui.xml.layout.loader.AbstractComponentLoader;\n\npublic class MySimplePaginationLoader extends AbstractComponentLoader<MySimplePagination> {\n\n    @Override\n    protected MySimplePagination createComponent() {\n        return factory.create(MySimplePagination.class);\n    }\n\n    @Override\n    public void loadComponent() {\n        // Load common attributes (id, visible, classNames, size, etc.) as needed.\n        // Example: size attributes\n        componentLoader().loadSizeAttributes(resultComponent, element);\n\n        // If you need specific attributes, use helper methods from the base class, e.g.:\n        // loadBoolean(element, "itemsPerPageVisible", resultComponent::setItemsPerPageVisible);\n\n        // For SimplePagination-specific wiring like dataLoader="..." you must implement\n        // the corresponding attribute handling here (resolve the referenced DataLoader and set it).\n        //\n        // (Exact attribute-to-API mapping depends on what you want to support in XML.)\n    }\n}\n```\n\nImportant: the official docs show how to build loaders and register them, but they don’t provide a ready-made “inherit all `simplePagination` XML attributes automatically”. So in your loader you explicitly implement the attributes you want to support (including `dataLoader` if you need the same binding as standard `<simplePagination dataLoader="..."/>`).\n\n## 3) Register the component and its loader in Spring\n\n```java\npackage com.company.app.config;\n\nimport com.company.app.component.MySimplePagination;\nimport com.company.app.component.loader.MySimplePaginationLoader;\nimport io.jmix.flowui.kit.component.ComponentRegistration;\nimport io.jmix.flowui.kit.component.ComponentRegistrationBuilder;\nimport org.springframework.context.annotation.Bean;\nimport org.springframework.context.annotation.Configuration;\n\n@Configuration\npublic class ComponentRegistrationConfiguration {\n\n    @Bean\n    public ComponentRegistration mySimplePagination() {\n        return ComponentRegistrationBuilder.create(MySimplePagination.class)\n                .withComponentLoader("mySimplePagination", MySimplePaginationLoader.class)\n                .build();\n    }\n}\n```\n\nAfter that, Jmix knows that an XML element named `mySimplePagination` should be created using your loader.\n\n## 4) Add XML namespace (XSD) to use it in view descriptors\n\nPer the docs, you define your own namespace and XSD (example shown for a custom `slider` component). Conceptually it will look like:\n\n```xml\n<xs:schema xmlns="http://company.com/schema/app-ui-components"\n           elementFormDefault="qualified"\n           targetNamespace="http://company.com/schema/app-ui-components"\n           xmlns:xs="http://www.w3.org/2001/XMLSchema"\n           xmlns:layout="http://jmix.io/schema/flowui/layout">\n\n    <xs:element name="mySimplePagination">\n        <xs:complexType>\n            <xs:complexContent>\n                <xs:extension base="layout:baseComponent">\n                    <!-- declare the XML attributes you want to support -->\n                    <xs:attribute name="itemsPerPageVisible" type="xs:boolean"/>\n                    <xs:attribute name="dataLoader" type="xs:string"/>\n                    <xs:attributeGroup ref="layout:hasSize"/>\n                </xs:extension>\n            </xs:complexContent>\n        </xs:complexType>\n    </xs:element>\n\n</xs:schema>\n```\n\nThen use it in the view XML:\n\n```xml\n<view xmlns="http://jmix.io/schema/flowui/view"\n      xmlns:app="http://company.com/schema/app-ui-components">\n    <layout>\n        <app:mySimplePagination id="pagination"\n                                itemsPerPageVisible="true"\n                                dataLoader="usersDl"/>\n    </layout>\n</view>\n```\n\n## 5) (Optional) Make it appear in Jmix Studio palette\n\nThe docs describe adding `@StudioUiKit` + `@StudioComponent` metadata for Studio support. This is optional for runtime.\n\n---\n\n### One clarification (so I don’t guess the wrong level of detail)\nDo you need your descendant to be used **as a new XML element** (like `<app:mySimplePagination/>`), or is it enough to use it **only from Java code** inside a view controller?
018c1f8e-3b7e-7f9e-c061-8f9a0b1c2d3e	1	system	2026-03-30 18:53:03.296+00	\N	2026-03-30 18:53:03.296+00	\N	\N	t	Jmix 1.x	What is vaadin 8 add-ons repository URL, for Jmix 1.x ?	I’m sorry, but I can only provide assistance with Jmix version 2 and above. If you have any questions related to Jmix 2+ or Java development, feel free to ask!
018c1b7e-3f8a-7e4a-8c2f-9b7d6e4a2c1f	1	system	2026-03-30 18:53:03.295+00	\N	2026-03-30 18:53:03.295+00	\N	\N	t	basic	What's new in the latest Jmix version?	The latest stable Jmix version in the docs is **Jmix 2.7**. Key changes:\n\n## Platform & Dependencies\n\n- **Vaadin updated to 24.9** (for Flow UI).\n- Minimal **IntelliJ IDEA** for Studio is now **2025.1**.\n- Standard upgrade path via Studio → *Jmix Tool Window → Settings → Project Properties → Change platform version*.\n\n---\n\n## Studio / Developer Experience\n\n- **Role Designer**\n  - Visualizes **role inheritance hierarchy**.\n  - Shows permissions inherited from base roles as **read‑only**.\n  - Entity permissions tab uses **color highlighting** for enabled/disabled elements.\n\n- **Entity Designer**\n  - New action **“Add attribute from DB”** → supports **database‑first** approach:\n    - Add column in DB → import as JPA attribute.\n  - New JPA Entity dialog: **“Create Data Repository”** checkbox:\n    - Automatically creates a Spring Data `JmixDataRepository` for the entity.\n    - Studio remembers the checkbox state.\n\n- **View Creation Wizard**\n  - If a repository exists for an entity, **“Use Data Repositories”** is pre‑selected and view will use repository delegates by default.\n\n---\n\n## New / Improved UI Components\n\nAll in Flow UI (Vaadin):\n\n- **`groupDataGrid`**\n  - Data grid with **grouping** by one or more columns.\n  - Provided by **Grouping Data Grid** add‑on.\n  \n- **`card`**\n  - Container for presenting content as a **card**.\n  - Good for structuring related content and actions.\n\n- **`gridLayout`**\n  - Layout based on **CSS Grid**.\n  - Two‑dimensional, responsive layout.\n  - Can host individual components, cards, or fragments.\n  - Alternative to data grids when you want free layout but grid‑like structure.\n\n- **`userMenu`**\n  - Displays current **logged‑in user** and related actions such as:\n    - **Substitute user**\n    - **Log out**\n  - Supports nested menu items and arbitrary UI components inside.\n  - Included by default in the **new project template** main view.\n\n---\n\n## Filtering & Data\n\n- **Date filtering**\n  - `genericFilter` and `propertyFilter`:\n    - New operation **“date equals”** ignoring time part of `datetime`.\n  - **Date Interval dialog**:\n    - Now supports **arbitrary start and end date**.\n    - Can replace manual `>=` / `<` conditions for “between” checks.\n\n---\n\n## Data Repositories\n\nChanges in `JmixDataRepository`:\n\n- Now extends **`org.springframework.data.repository.ListCrudRepository`**:\n  - All `findAll()` methods that returned `Iterable` now return `List`.\n- Supports queries returning **scalar values and aggregates** using `@Query`.\n- Methods returning `Page` now **always do a count query** for total items/pages.\n  - If you don’t need totals, use new **`findAllSlice()`** methods returning `Slice` to avoid the extra count query.\n- New delegate handlers for views:\n  - **`loadFromRepositoryDelegate`**\n  - **`totalCountByRepositoryDelegate`**\n  - Allow calling repositories directly in views without converting `LoadContext` params.\n  - Studio will wire these automatically if you choose **“Use Data Repositories”** in the wizard.\n\n---\n\n## Reports Add‑on\n\n- **Design‑time reports**:\n  - Define report structure in **annotated Java classes** instead of pure runtime configuration.\n  - Benefits:\n    - Stored in **version control** (Git).\n    - Shipping report changes with app releases.\n    - Can use **debugger / IDE tools** on report logic.\n  - Trade‑off: runtime users **cannot modify** design‑time reports in the UI.\n- Report entity:\n  - **Report code is now mandatory** and must be unique among **all runtime and design‑time** reports.\n\n---\n\n## REST DataStore Add‑on\n\n- New option to **share entities and service interfaces** between applications using REST DataStore.\n  - Tighter coupling, but **less code duplication** and simpler integration.\n  - See docs section “REST DataStore: Sharing Code Between Applications”.\n\n---\n\n## Entity Log\n\n- Now can **track changes in many‑to‑many attributes** (on the owning side).\n\n---\n\n## Breaking Changes to Watch\n\n1. **`JmixDataRepository` method overrides**\n   - If you have custom `findAll` overrides, you must change the return type to `List`:\n   ```java\n   public interface OrderRepository extends JmixDataRepository<Order, UUID> {\n\n       // before\n       // @Override\n       // Iterable<Order> findAll(Sort sort, @Nullable FetchPlan fetchPlan);\n\n       // after\n       @Override\n       List<Order> findAll(Sort sort, @Nullable FetchPlan fetchPlan);\n   }\n   ```\n\n2. **Report code required**\n   - Any report without code must be assigned a **unique code** when edited.\n\n3. **Class move**\n   - `io.jmix.flowui.model.impl.EntityValuesComparator` moved to `io.jmix.core.comparator`.\n   - Update imports if you use it.\n\n4. **Regressions in 2.7.0**\n   - Known issues **#4850** and **#4852** affect **frontend‑only** projects; 2.7.1 has fixes.\n   - Check the issue comments if you’re on 2.7.0.
019ae485-f351-7642-98b1-44b3340e6000	1	system	2026-03-30 18:53:03.296+00	\N	2026-03-30 18:53:03.296+00	\N	\N	t	ui	How to save custom text value in current view URL?	Use the `urlQueryParameters` facet – it’s exactly for binding view state to the current URL (including custom values).\n\nBelow is a minimal example for **saving a text field value in the URL** and restoring it when you refresh or navigate back.\n\n### 1. View XML\n\n```xml\n<view xmlns="http://jmix.io/schema/flowui/view">\n    <facets>\n        <urlQueryParameters id="urlQueryParameters"/>\n    </facets>\n\n    <layout>\n        <textField id="sampleTextField" datatype="string"/>\n    </layout>\n</view>\n```\n\n### 2. View Controller\n\n```java\n@ViewController("sample-view")\n@ViewDescriptor("sample-view.xml")\npublic class SampleView extends StandardView {\n\n    private static final String TEXT_URL_PARAM = "text";\n\n    @ViewComponent\n    private UrlQueryParametersFacet urlQueryParameters;\n\n    @ViewComponent\n    private TypedTextField<String> sampleTextField;\n\n    private class SampleUrlQueryParametersBinder extends AbstractUrlQueryParametersBinder {\n\n        public SampleUrlQueryParametersBinder() {\n            // When text changes – update URL\n            sampleTextField.addValueChangeListener(event -> {\n                String text = event.getValue();\n\n                QueryParameters qp = new QueryParameters(\n                        Map.of(\n                                TEXT_URL_PARAM,\n                                text != null && !text.isEmpty()\n                                        ? Collections.singletonList(text)\n                                        : Collections.emptyList()\n                        )\n                );\n\n                fireQueryParametersChanged(\n                        new UrlQueryParametersFacet.UrlQueryParametersChangeEvent(this, qp)\n                );\n            });\n        }\n\n        @Override\n        public void updateState(QueryParameters queryParameters) {\n            // Apply URL value to the field when the view is (re)opened\n            List<String> textStrings = queryParameters.getParameters().get(TEXT_URL_PARAM);\n            if (textStrings != null && !textStrings.isEmpty()) {\n                sampleTextField.setValue(textStrings.get(0));\n            }\n        }\n\n        @Override\n        public Component getComponent() {\n            return null; // we don’t bind to a specific visual component\n        }\n    }\n\n    @Subscribe\n    public void onInit(InitEvent event) {\n        urlQueryParameters.registerBinder(new SampleUrlQueryParametersBinder());\n    }\n}\n```
019c8569-0e46-751d-97f0-51cf198b0541	1	system	2026-03-30 18:53:03.296+00	\N	2026-03-30 18:53:03.296+00	\N	\N	t	data access	I need to count records in some table. How to do it?	In Jmix 2+ the usual way to count rows is to execute an aggregate JPQL query via `DataManager.loadValue()`.\n\n## Count all rows of an entity\n```java\nimport io.jmix.core.DataManager;\nimport org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.stereotype.Component;\n\n@Component\npublic class UserStatService {\n\n    @Autowired\n    private DataManager dataManager;\n\n    public long countUsers() {\n        return dataManager.loadValue("select count(e) from User e", Long.class)\n                .one();\n    }\n}\n```\n\nNotes:\n- Use the **entity name** in JPQL (`User`), not the DB table name.\n- Prefer `Long.class` for `count(...)` results.\n\n## Count with conditions\n```java\nimport io.jmix.core.DataManager;\nimport org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.stereotype.Component;\n\n@Component\npublic class UserStatService {\n\n    @Autowired\n    private DataManager dataManager;\n\n    public long countActiveUsers() {\n        return dataManager.loadValue(\n                        "select count(e) from User e where e.active = true",\n                        Long.class)\n                .one();\n    }\n}\n```\n\n## If you use Spring Data repositories\nIf you have a repository for the entity, you can also use its `count()` methods (or, in UI pagination scenarios, `count(context)` as shown in the docs).\n\n---\n\nIf you meant **count rows in a physical table that is not mapped to a JPA entity**, tell me what it is (JPA entity vs plain SQL table/view), and I’ll suggest the appropriate approach.
\.


--
-- Data for Name: check_run; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.check_run (id, created_by, created_date, parameters, score) FROM stdin;
\.


--
-- Data for Name: databasechangelog; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype, md5sum, description, comments, tag, liquibase, contexts, labels, deployment_id) FROM stdin;
1	flowui-data	io/jmix/flowuidata/liquibase/changelog/001-flowui-data.xml	2026-03-30 18:52:56.984819	1	EXECUTED	9:324669760cb7b5fd957862395815b17f	createTable tableName=FLOWUI_FILTER_CONFIGURATION		\N	4.25.1	\N	\N	4896776907
2	flowui-data	io/jmix/flowuidata/liquibase/changelog/001-flowui-data.xml	2026-03-30 18:52:56.997378	2	EXECUTED	9:1c62775191bad1cb40e9e1b56dbefb0e	addColumn tableName=FLOWUI_FILTER_CONFIGURATION		\N	4.25.1	\N	\N	4896776907
3	flowui-data	io/jmix/flowuidata/liquibase/changelog/001-flowui-data.xml	2026-03-30 18:52:57.020378	3	EXECUTED	9:148d3d2943d927842d1e5c3386491e06	addColumn tableName=FLOWUI_FILTER_CONFIGURATION; renameColumn newColumnName=CONFIGURATION_ID, oldColumnName=CODE, tableName=FLOWUI_FILTER_CONFIGURATION		\N	4.25.1	\N	\N	4896776907
4	flowui-data	io/jmix/flowuidata/liquibase/changelog/001-flowui-data.xml	2026-03-30 18:52:57.051324	4	EXECUTED	9:8e996eb484379f0586f09c73e050b35e	createTable tableName=FLOWUI_USER_SETTINGS		\N	4.25.1	\N	\N	4896776907
1	flowui-data	io/jmix/flowuidata/liquibase/changelog/002-flowui-data.xml	2026-03-30 18:52:57.063489	5	EXECUTED	9:1c044190153440bc97f2099964af0323	modifyDataType columnName=CREATED_BY, tableName=FLOWUI_USER_SETTINGS		\N	4.25.1	\N	\N	4896776907
1	security-data	io/jmix/securitydata/liquibase/changelog/001-security-data.xml	2026-03-30 18:52:57.114276	6	EXECUTED	9:70d3260b781ef95a05b7e0cbc09c16cb	createTable tableName=SEC_RESOURCE_ROLE; createTable tableName=SEC_ROW_LEVEL_ROLE; createTable tableName=SEC_RESOURCE_POLICY; createTable tableName=SEC_ROW_LEVEL_POLICY; createTable tableName=SEC_ROLE_ASSIGNMENT		\N	4.25.1	\N	\N	4896776907
2-postgresql	security-data	io/jmix/securitydata/liquibase/changelog/001-security-data.xml	2026-03-30 18:52:57.124186	7	EXECUTED	9:eb2b89d45ccff645477bd7f62af879a4	createIndex indexName=IDX_RESOURCE_ROLE_UN_C, tableName=SEC_RESOURCE_ROLE		\N	4.25.1	\N	\N	4896776907
3-postgresql	security-data	io/jmix/securitydata/liquibase/changelog/001-security-data.xml	2026-03-30 18:52:57.132158	8	EXECUTED	9:eb208e1e9bb1c5418ad6f3918c92807d	createIndex indexName=IDX_ROW_LEVEL_ROLE_UN_C, tableName=SEC_ROW_LEVEL_ROLE		\N	4.25.1	\N	\N	4896776907
4	security-data	io/jmix/securitydata/liquibase/changelog/001-security-data.xml	2026-03-30 18:52:57.142304	9	EXECUTED	9:3c2cba50afcdb73c23df1e7b3a4a3bb9	createTable tableName=persistent_logins		\N	4.25.1	\N	\N	4896776907
1	security-data	io/jmix/securitydata/liquibase/changelog/002-security-data.xml	2026-03-30 18:52:57.148762	10	EXECUTED	9:b7a285528fe0552ff7602cdcefa5dc82	addColumn tableName=SEC_RESOURCE_ROLE; addColumn tableName=SEC_ROW_LEVEL_ROLE		\N	4.25.1	\N	\N	4896776907
2	security-data	io/jmix/securitydata/liquibase/changelog/002-security-data.xml	2026-03-30 18:52:57.153232	11	EXECUTED	9:a64ca8e29adbc4a957553c7c8a7734fb	addColumn tableName=SEC_RESOURCE_ROLE		\N	4.25.1	\N	\N	4896776907
1	security-data	io/jmix/securitydata/liquibase/changelog/003-security-data.xml	2026-03-30 18:52:57.164196	12	EXECUTED	9:7c91ca84290c39c281ff02056aa15d73	createTable tableName=SEC_USER_SUBSTITUTION		\N	4.25.1	!cuba	\N	4896776907
1	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/010-init-user.xml	2026-03-30 18:52:57.176513	13	EXECUTED	9:cbb1a286a79646aca73adc1582204021	createTable tableName=USER_		\N	4.25.1	!cuba	\N	4896776907
2	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/010-init-user.xml	2026-03-30 18:52:57.183013	14	EXECUTED	9:6b757d74ec527c55bf8dbc5c003359a7	createIndex indexName=IDX_USER__ON_USERNAME, tableName=USER_		\N	4.25.1	!cuba	\N	4896776907
3	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/010-init-user.xml	2026-03-30 18:52:57.189031	15	EXECUTED	9:7ab7f4dae818108d1bbed0067b6f0509	insert tableName=USER_; insert tableName=USER_		\N	4.25.1	!cuba	\N	4896776907
4	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/010-init-user.xml	2026-03-30 18:52:57.194392	16	EXECUTED	9:46e15b322945039a7d69957e9b0b19b7	insert tableName=SEC_ROLE_ASSIGNMENT; insert tableName=SEC_ROLE_ASSIGNMENT		\N	4.25.1	!cuba	\N	4896776907
1	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/07/04-155244-14fa14f2.xml	2026-03-30 18:52:57.206836	17	EXECUTED	9:8566d5ebbaa16abe2347a70392d85ab9	createTable tableName=PARAMETERS		\N	4.25.1	\N	\N	4896776907
1	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/07/25-181524-14fa14f2.xml	2026-03-30 18:52:57.219643	18	EXECUTED	9:708c40ae7fd6fde6784a1f85b1cd57a5	createTable tableName=CHECK_		\N	4.25.1	\N	\N	4896776907
2	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/07/25-181524-14fa14f2.xml	2026-03-30 18:52:57.231443	19	EXECUTED	9:78f5e0e859c2a133ee732edadab9d6d1	createTable tableName=CHECK_DEF		\N	4.25.1	\N	\N	4896776907
3	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/07/25-181524-14fa14f2.xml	2026-03-30 18:52:57.242323	20	EXECUTED	9:75d1f5a2e4701225bc38dd1941bb343a	createTable tableName=CHECK_RUN		\N	4.25.1	\N	\N	4896776907
4	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/07/25-181524-14fa14f2.xml	2026-03-30 18:52:57.252732	21	EXECUTED	9:d2f2df816bea00b0fa2d384d54442ef9	createIndex indexName=IDX_CHECK__CHECK_DEF, tableName=CHECK_; addForeignKeyConstraint baseTableName=CHECK_, constraintName=FK_CHECK__ON_CHECK_DEF, referencedTableName=CHECK_DEF		\N	4.25.1	\N	\N	4896776907
5	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/07/25-181524-14fa14f2.xml	2026-03-30 18:52:57.260884	22	EXECUTED	9:168035f05931508f6196c4caf217c224	createIndex indexName=IDX_CHECK__CHECK_RUN, tableName=CHECK_; addForeignKeyConstraint baseTableName=CHECK_, constraintName=FK_CHECK__ON_CHECK_RUN, referencedTableName=CHECK_RUN		\N	4.25.1	\N	\N	4896776907
1	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/10/21-140840-14fa14f2.xml	2026-03-30 18:52:57.271471	23	EXECUTED	9:bd412d3c062d5a4fbf84733825ba4cd5	createTable tableName=CHAT_LOG		\N	4.25.1	\N	\N	4896776907
2	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/10/21-140840-14fa14f2.xml	2026-03-30 18:52:57.279143	24	EXECUTED	9:a272e2ae51223aa41a649eade12b68f2	createIndex indexName=IDX_CHAT_LOG_CONVERSATION_ID, tableName=CHAT_LOG		\N	4.25.1	\N	\N	4896776907
3	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/10/21-140840-14fa14f2.xml	2026-03-30 18:52:57.285723	25	EXECUTED	9:fb7a43d82b712f3832f9f961509798b5	createIndex indexName=IDX_CHAT_LOG_DATE, tableName=CHAT_LOG		\N	4.25.1	\N	\N	4896776907
1	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/10/29-172627-14fa14f2.xml	2026-03-30 18:52:57.290828	26	EXECUTED	9:93f8ffd761323c99b0f09acc38043749	addColumn tableName=CHAT_LOG		\N	4.25.1	\N	\N	4896776907
1	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/12/18-123715-de4fa26e.xml	2026-03-30 18:52:57.295517	27	EXECUTED	9:18a3d7c0ab15848ed0fc05468aee6064	addColumn tableName=PARAMETERS		\N	4.25.1	\N	\N	4896776907
2	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/12/18-123715-de4fa26e.xml	2026-03-30 18:52:57.303002	28	EXECUTED	9:bc987d8d63d2f211fef5a4ebf092c1a9	update tableName=PARAMETERS		\N	4.25.1	\N	\N	4896776907
3	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2025/12/18-123715-de4fa26e.xml	2026-03-30 18:52:57.307767	29	EXECUTED	9:55bbf978f80acbac62c5fb3a3a639709	addNotNullConstraint columnName=TARGET_TYPE, tableName=PARAMETERS		\N	4.25.1	\N	\N	4896776907
1	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2026/02/18-100000-semantic-score.xml	2026-03-30 18:52:57.317365	30	EXECUTED	9:fb8ab2caab3f22b3b5fb7186b92b92e6	dropColumn columnName=ROUGE_SCORE, tableName=CHECK_; dropColumn columnName=BERT_SCORE, tableName=CHECK_; dropColumn columnName=ROUGE_SCORE, tableName=CHECK_RUN; dropColumn columnName=BERT_SCORE, tableName=CHECK_RUN; dropColumn columnName=ROUGE, ta...		\N	4.25.1	\N	\N	4896776907
1	jmix-ai-backend	io/jmix/ai/backend/liquibase/changelog/2026/02/19-101500-score-only.xml	2026-03-30 18:52:57.337948	31	EXECUTED	9:bf1df8e038d135dfbc66e4811de23432	addColumn tableName=CHECK_; addColumn tableName=CHECK_RUN; sql; sql; dropColumn columnName=SCRIPT_SCORE, tableName=CHECK_; dropColumn columnName=SEMANTIC_SCORE, tableName=CHECK_; dropColumn columnName=SCRIPT_SCORE, tableName=CHECK_RUN; dropColumn ...		\N	4.25.1	\N	\N	4896776907
\.


--
-- Data for Name: databasechangeloglock; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.databasechangeloglock (id, locked, lockgranted, lockedby) FROM stdin;
1	f	\N	\N
\.


--
-- Data for Name: flowui_filter_configuration; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.flowui_filter_configuration (id, component_id, configuration_id, username, root_condition, sys_tenant_id, name, default_for_all) FROM stdin;
\.


--
-- Data for Name: flowui_user_settings; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.flowui_user_settings (id, create_ts, created_by, username, key_, value_) FROM stdin;
\.


--
-- Data for Name: parameters; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.parameters (id, version, created_date, created_by, last_modified_date, last_modified_by, active, content, target_type) FROM stdin;
\.


--
-- Data for Name: persistent_logins; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.persistent_logins (series, username, token, last_used) FROM stdin;
\.


--
-- Data for Name: sec_resource_policy; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.sec_resource_policy (id, version, create_ts, created_by, update_ts, updated_by, delete_ts, deleted_by, type_, policy_group, resource_, action_, effect, role_id) FROM stdin;
\.


--
-- Data for Name: sec_resource_role; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.sec_resource_role (id, version, create_ts, created_by, update_ts, updated_by, delete_ts, deleted_by, name, code, child_roles, sys_tenant_id, description, scopes) FROM stdin;
\.


--
-- Data for Name: sec_role_assignment; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.sec_role_assignment (id, version, create_ts, created_by, update_ts, updated_by, delete_ts, deleted_by, username, role_code, role_type) FROM stdin;
6c9e420a-2b7a-4c42-8654-a9027ee14083	1	\N	\N	\N	\N	\N	\N	admin	system-full-access	resource
\.


--
-- Data for Name: sec_row_level_policy; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.sec_row_level_policy (id, version, create_ts, created_by, update_ts, updated_by, delete_ts, deleted_by, type_, action_, entity_name, where_clause, join_clause, script_, role_id) FROM stdin;
\.


--
-- Data for Name: sec_row_level_role; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.sec_row_level_role (id, version, create_ts, created_by, update_ts, updated_by, delete_ts, deleted_by, name, code, child_roles, sys_tenant_id, description) FROM stdin;
\.


--
-- Data for Name: sec_user_substitution; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.sec_user_substitution (id, version, create_ts, created_by, update_ts, updated_by, delete_ts, deleted_by, username, substituted_username, start_date, end_date) FROM stdin;
\.


--
-- Data for Name: spring_ai_chat_memory; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.spring_ai_chat_memory (conversation_id, content, type, "timestamp") FROM stdin;
\.


--
-- Data for Name: user_; Type: TABLE DATA; Schema: public; Owner: root
--

COPY public.user_ (id, version, username, first_name, last_name, password, email, active, time_zone_id) FROM stdin;
60885987-1b61-4247-94c7-dff348347f93	1	admin	\N	\N	{noop}admin	\N	t	\N
\.


--
-- Name: flowui_filter_configuration FLOWUI_FILTER_CONFIGURATION_pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.flowui_filter_configuration
    ADD CONSTRAINT "FLOWUI_FILTER_CONFIGURATION_pkey" PRIMARY KEY (id);


--
-- Name: flowui_user_settings FLOWUI_USER_SETTINGS_pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.flowui_user_settings
    ADD CONSTRAINT "FLOWUI_USER_SETTINGS_pkey" PRIMARY KEY (id);


--
-- Name: sec_resource_policy SEC_RESOURCE_POLICY_pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.sec_resource_policy
    ADD CONSTRAINT "SEC_RESOURCE_POLICY_pkey" PRIMARY KEY (id);


--
-- Name: sec_resource_role SEC_RESOURCE_ROLE_pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.sec_resource_role
    ADD CONSTRAINT "SEC_RESOURCE_ROLE_pkey" PRIMARY KEY (id);


--
-- Name: sec_role_assignment SEC_ROLE_ASSIGNMENT_pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.sec_role_assignment
    ADD CONSTRAINT "SEC_ROLE_ASSIGNMENT_pkey" PRIMARY KEY (id);


--
-- Name: sec_row_level_policy SEC_ROW_LEVEL_POLICY_pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.sec_row_level_policy
    ADD CONSTRAINT "SEC_ROW_LEVEL_POLICY_pkey" PRIMARY KEY (id);


--
-- Name: sec_row_level_role SEC_ROW_LEVEL_ROLE_pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.sec_row_level_role
    ADD CONSTRAINT "SEC_ROW_LEVEL_ROLE_pkey" PRIMARY KEY (id);


--
-- Name: sec_user_substitution SEC_USER_SUBSTITUTION_pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.sec_user_substitution
    ADD CONSTRAINT "SEC_USER_SUBSTITUTION_pkey" PRIMARY KEY (id);


--
-- Name: user_ USER__pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.user_
    ADD CONSTRAINT "USER__pkey" PRIMARY KEY (id);


--
-- Name: databasechangeloglock databasechangeloglock_pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.databasechangeloglock
    ADD CONSTRAINT databasechangeloglock_pkey PRIMARY KEY (id);


--
-- Name: persistent_logins persistent_logins_pkey; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.persistent_logins
    ADD CONSTRAINT persistent_logins_pkey PRIMARY KEY (series);


--
-- Name: chat_log pk_chat_log; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.chat_log
    ADD CONSTRAINT pk_chat_log PRIMARY KEY (id);


--
-- Name: check_ pk_check_; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.check_
    ADD CONSTRAINT pk_check_ PRIMARY KEY (id);


--
-- Name: check_def pk_check_def; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.check_def
    ADD CONSTRAINT pk_check_def PRIMARY KEY (id);


--
-- Name: check_run pk_check_run; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.check_run
    ADD CONSTRAINT pk_check_run PRIMARY KEY (id);


--
-- Name: parameters pk_parameters; Type: CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.parameters
    ADD CONSTRAINT pk_parameters PRIMARY KEY (id);


--
-- Name: idx_chat_log_conversation_id; Type: INDEX; Schema: public; Owner: root
--

CREATE INDEX idx_chat_log_conversation_id ON public.chat_log USING btree (conversation_id);


--
-- Name: idx_chat_log_date; Type: INDEX; Schema: public; Owner: root
--

CREATE INDEX idx_chat_log_date ON public.chat_log USING btree (created_date);


--
-- Name: idx_check__check_def; Type: INDEX; Schema: public; Owner: root
--

CREATE INDEX idx_check__check_def ON public.check_ USING btree (check_def_id);


--
-- Name: idx_check__check_run; Type: INDEX; Schema: public; Owner: root
--

CREATE INDEX idx_check__check_run ON public.check_ USING btree (check_run_id);


--
-- Name: idx_resource_role_un_c; Type: INDEX; Schema: public; Owner: root
--

CREATE INDEX idx_resource_role_un_c ON public.sec_resource_role USING btree (code) WHERE (delete_ts IS NULL);


--
-- Name: idx_row_level_role_un_c; Type: INDEX; Schema: public; Owner: root
--

CREATE INDEX idx_row_level_role_un_c ON public.sec_row_level_role USING btree (code) WHERE (delete_ts IS NULL);


--
-- Name: idx_user__on_username; Type: INDEX; Schema: public; Owner: root
--

CREATE UNIQUE INDEX idx_user__on_username ON public.user_ USING btree (username);


--
-- Name: spring_ai_chat_memory_conversation_id_timestamp_idx; Type: INDEX; Schema: public; Owner: root
--

CREATE INDEX spring_ai_chat_memory_conversation_id_timestamp_idx ON public.spring_ai_chat_memory USING btree (conversation_id, "timestamp");


--
-- Name: check_ fk_check__on_check_def; Type: FK CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.check_
    ADD CONSTRAINT fk_check__on_check_def FOREIGN KEY (check_def_id) REFERENCES public.check_def(id);


--
-- Name: check_ fk_check__on_check_run; Type: FK CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.check_
    ADD CONSTRAINT fk_check__on_check_run FOREIGN KEY (check_run_id) REFERENCES public.check_run(id);


--
-- Name: sec_resource_policy fk_res_policy_role; Type: FK CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.sec_resource_policy
    ADD CONSTRAINT fk_res_policy_role FOREIGN KEY (role_id) REFERENCES public.sec_resource_role(id);


--
-- Name: sec_row_level_policy fk_row_level_policy_role; Type: FK CONSTRAINT; Schema: public; Owner: root
--

ALTER TABLE ONLY public.sec_row_level_policy
    ADD CONSTRAINT fk_row_level_policy_role FOREIGN KEY (role_id) REFERENCES public.sec_row_level_role(id);


--
-- PostgreSQL database dump complete
--

\unrestrict N4nFeEqDzzmcbmKzUQCuOn3WUsRWFO3hk0Tm3d7MUDoR7h4E1rYqRmzIjyYeb98

