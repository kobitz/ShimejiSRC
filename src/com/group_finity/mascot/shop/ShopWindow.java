package com.group_finity.mascot.shop;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.group_finity.mascot.Main;

/**
 * "Mascot Shop": browse a curated catalog. Free, redistributable mascots install in one click
 * (https + SHA-256 verified + safe unzip). Mascots hosted by their creators (Cachomon, DeviantArt,
 * Patreon, Ko-fi ...) open the creator's page; the user downloads there and brings the zip back
 * with "Import .zip". The shop never scrapes or re-hosts a creator's files.
 */
public final class ShopWindow extends JDialog
{
    static final String DEFAULT_CATALOG_URL = "https://raw.githubusercontent.com/kobitz/ShimejiSRC/main/conf/shop_catalog.json";
    private static ShopWindow open;

    private final JPanel list = new JPanel();
    private final JTextField search = new JTextField( 18 );
    private final JCheckBox freeOnly = new JCheckBox( "Free only" );
    private final JLabel status = new JLabel( " " );
    private final JProgressBar bar = new JProgressBar();
    private ShopCatalog catalog = null;
    private boolean busy = false;

    public static void showShop( final JFrame owner )
    {
        if( open != null && open.isDisplayable() ) { open.toFront(); return; }
        open = new ShopWindow( owner );
        open.setVisible( true );
    }

    private ShopWindow( final JFrame owner )
    {
        super( owner, "Mascot Shop", false );
        setDefaultCloseOperation( DISPOSE_ON_CLOSE );
        final java.awt.Image icon = owner != null ? owner.getIconImage() : null;
        if( icon != null ) setIconImage( icon );

        final JPanel top = new JPanel( new FlowLayout( FlowLayout.LEFT, 8, 6 ) );
        top.add( new JLabel( "Search" ) );
        top.add( search );
        top.add( freeOnly );
        final JButton refresh = new JButton( "Refresh" );
        final JButton imp = new JButton( "Import .zip..." );
        imp.setToolTipText( "Install a mascot zip you downloaded from its creator's page" );
        top.add( refresh );
        top.add( imp );

        list.setLayout( new BoxLayout( list, BoxLayout.Y_AXIS ) );
        final JScrollPane scroll = new JScrollPane( list );
        scroll.getVerticalScrollBar().setUnitIncrement( 16 );
        scroll.setBorder( BorderFactory.createEmptyBorder() );

        bar.setVisible( false );
        final JPanel bottom = new JPanel( new BorderLayout( 8, 0 ) );
        bottom.setBorder( BorderFactory.createEmptyBorder( 4, 8, 6, 8 ) );
        bottom.add( status, BorderLayout.CENTER );
        bottom.add( bar, BorderLayout.EAST );

        add( top, BorderLayout.NORTH );
        add( scroll, BorderLayout.CENTER );
        add( bottom, BorderLayout.SOUTH );
        setSize( 720, 560 );
        setLocationRelativeTo( owner );

        search.getDocument().addDocumentListener( new DocumentListener()
        {
            public void insertUpdate( final DocumentEvent e ) { rebuild(); }
            public void removeUpdate( final DocumentEvent e ) { rebuild(); }
            public void changedUpdate( final DocumentEvent e ) { rebuild(); }
        } );
        freeOnly.addActionListener( e -> rebuild() );
        refresh.addActionListener( e -> loadCatalog() );
        imp.addActionListener( e -> importZip() );
        loadCatalog();
    }

    // ── catalog ──────────────────────────────────────────────────────────────

    private void loadCatalog()
    {
        status.setText( "Loading catalog..." );
        new SwingWorker<Object[], Void>()
        {
            @Override protected Object[] doInBackground()
            {
                final String url = Main.getInstance().getProperties().getProperty( "ShopCatalogUrl", DEFAULT_CATALOG_URL ).trim();
                try
                {
                    return new Object[] { ShopCatalog.parse( ShopDownloader.fetchText( url ) ), "" };
                }
                catch( final Exception online )
                {
                    try
                    {
                        final Path local = Paths.get( "conf", "shop_catalog.json" );
                        final String text = Files.exists( local )
                            ? new String( Files.readAllBytes( local ), java.nio.charset.StandardCharsets.UTF_8 )
                            : new String( ShopWindow.class.getResourceAsStream( "/shop_catalog.json" ).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8 );
                        return new Object[] { ShopCatalog.parse( text ), "Offline - showing the built-in list." };
                    }
                    catch( final Exception offline )
                    {
                        return new Object[] { null, "Couldn't load the catalog: " + online.getMessage() };
                    }
                }
            }
            @Override protected void done()
            {
                try
                {
                    final Object[] r = get();
                    catalog = (ShopCatalog) r[ 0 ];
                    status.setText( r[ 1 ].toString().isEmpty() && catalog != null ? catalog.entries().size() + " mascots" : r[ 1 ].toString() );
                }
                catch( final Exception e ) { status.setText( "Couldn't load the catalog." ); }
                rebuild();
            }
        }.execute();
    }

    private void rebuild()
    {
        list.removeAll();
        if( catalog != null )
        {
            final String q = search.getText().trim().toLowerCase( Locale.ROOT );
            for( final ShopEntry e : catalog.entries() )
            {
                if( freeOnly.isSelected() && !e.isFree() ) continue;
                final String hay = ( e.name + " " + e.creator + " " + e.description + " " + e.tags ).toLowerCase( Locale.ROOT );
                if( !q.isEmpty() && !hay.contains( q ) ) continue;
                list.add( card( e ) );
            }
        }
        if( list.getComponentCount() == 0 ) list.add( new JLabel( "  Nothing to show." ) );
        list.add( Box.createVerticalGlue() );
        list.revalidate();
        list.repaint();
    }

    private static boolean installed( final ShopEntry e )
    {
        return Files.isDirectory( Paths.get( "img", e.id ) );
    }

    private JPanel card( final ShopEntry e )
    {
        final JPanel c = new JPanel( new BorderLayout( 10, 0 ) );
        c.setBorder( BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder( 4, 8, 4, 8 ),
            BorderFactory.createCompoundBorder( BorderFactory.createLineBorder( new java.awt.Color( 128, 128, 128, 90 ) ),
                                               BorderFactory.createEmptyBorder( 8, 10, 8, 10 ) ) ) );
        c.setMaximumSize( new Dimension( Integer.MAX_VALUE, 150 ) );

        final JPanel text = new JPanel();
        text.setLayout( new BoxLayout( text, BoxLayout.Y_AXIS ) );
        text.setOpaque( false );
        final JLabel title = new JLabel( e.name + "   " + ( e.isFree() ? "Free" : e.price ) );
        title.setFont( title.getFont().deriveFont( Font.BOLD ) );
        final String by = ( e.creator.isEmpty() ? "" : "by " + e.creator ) + ( e.source.isEmpty() ? "" : "  -  " + e.source );
        final JLabel sub = new JLabel( by.isEmpty() ? " " : by );
        final JTextArea desc = new JTextArea( e.description );
        desc.setLineWrap( true );
        desc.setWrapStyleWord( true );
        desc.setEditable( false );
        desc.setOpaque( false );
        desc.setFocusable( false );
        desc.setBorder( BorderFactory.createEmptyBorder( 4, 0, 0, 0 ) );
        text.add( title );
        text.add( sub );
        text.add( desc );

        final JPanel buttons = new JPanel();
        buttons.setLayout( new BoxLayout( buttons, BoxLayout.Y_AXIS ) );
        buttons.setOpaque( false );
        if( e.mode == ShopEntry.Mode.INSTALL )
        {
            final JButton b = new JButton( installed( e ) ? "Installed" : "Install" );
            b.setEnabled( !installed( e ) );
            b.addActionListener( ev -> install( e, b ) );
            buttons.add( b );
        }
        else
        {
            final JButton b = new JButton( e.isFree() ? "Open download page" : "Get from creator" );
            b.addActionListener( ev -> browse( e.openUrl() ) );
            buttons.add( b );
            if( !e.pageUrl.isEmpty() && !e.pageUrl.equals( e.openUrl() ) )
            {
                buttons.add( Box.createVerticalStrut( 4 ) );
                final JButton p = new JButton( "View page" );
                p.addActionListener( ev -> browse( e.pageUrl ) );
                buttons.add( p );
            }
            if( installed( e ) )
            {
                buttons.add( Box.createVerticalStrut( 4 ) );
                final JButton i = new JButton( "Installed" );
                i.setEnabled( false );
                buttons.add( i );
            }
        }
        c.add( text, BorderLayout.CENTER );
        c.add( buttons, BorderLayout.EAST );
        return c;
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private void browse( final String url )
    {
        try
        {
            if( !ShopEntry.isWebUrl( url, false ) ) return;
            final Desktop d = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if( d != null && d.isSupported( Desktop.Action.BROWSE ) ) { d.browse( new URI( url ) ); return; }
        }
        catch( final Exception ignored ) { }
        JOptionPane.showMessageDialog( this, "Couldn't open the browser. The address is:\n" + url );
    }

    private void install( final ShopEntry e, final JButton button )
    {
        if( busy ) return;
        setBusy( true, "Downloading " + e.name + "..." );
        new SwingWorker<List<String>, Long>()
        {
            @Override protected List<String> doInBackground() throws Exception
            {
                final Path tmp = Files.createTempFile( "shimeji-shop-", ".zip" );
                try
                {
                    ShopDownloader.downloadVerified( e.downloadUrl, tmp, e.sha256, n -> publish( n ) );
                    return MascotPackage.installZip( tmp, Paths.get( "img" ), e.id );
                }
                finally { Files.deleteIfExists( tmp ); }
            }
            @Override protected void process( final List<Long> chunks )
            {
                status.setText( "Downloading " + e.name + "... " + chunks.get( chunks.size() - 1 ) / 1024 + " KB" );
            }
            @Override protected void done() { finished( this, "Installed" ); }
        }.execute();
    }

    private void importZip()
    {
        if( busy ) return;
        final JFileChooser fc = new JFileChooser();
        fc.setDialogTitle( "Choose a mascot zip you downloaded" );
        fc.setFileFilter( new FileNameExtensionFilter( "Zip files", "zip" ) );
        if( fc.showOpenDialog( this ) != JFileChooser.APPROVE_OPTION ) return;
        final File f = fc.getSelectedFile();
        String base = f.getName();
        if( base.toLowerCase( Locale.ROOT ).endsWith( ".zip" ) ) base = base.substring( 0, base.length() - 4 );
        final String fallback = base;
        setBusy( true, "Installing " + f.getName() + "..." );
        new SwingWorker<List<String>, Void>()
        {
            @Override protected List<String> doInBackground() throws Exception
            {
                return MascotPackage.installZip( f.toPath(), Paths.get( "img" ), fallback );
            }
            @Override protected void done() { finished( this, "Imported" ); }
        }.execute();
    }

    private void setBusy( final boolean b, final String msg )
    {
        busy = b;
        bar.setIndeterminate( b );
        bar.setVisible( b );
        status.setText( msg );
    }

    private void finished( final SwingWorker<List<String>, ?> w, final String verb )
    {
        setBusy( false, " " );
        try
        {
            final List<String> names = w.get();
            rebuild();
            status.setText( verb + ": " + String.join( ", ", names ) );
            final int a = JOptionPane.showConfirmDialog( this,
                verb + " " + String.join( ", ", names ) + ".\nCall " + ( names.size() > 1 ? "them" : "it" ) + " out now?",
                "Mascot Shop", JOptionPane.YES_NO_OPTION );
            if( a == JOptionPane.YES_OPTION )
                for( final String n : names ) Main.getInstance().activateImageSet( n );
        }
        catch( final Exception ex )
        {
            Throwable t = ex.getCause() != null ? ex.getCause() : ex;
            status.setText( "Nothing was installed." );
            JOptionPane.showMessageDialog( this, t.getMessage(), "Mascot Shop", JOptionPane.WARNING_MESSAGE );
        }
    }
}
